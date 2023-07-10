/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.processor.instrument.impl

import io.vertx.core.*
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.serviceproxy.ServiceException
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.common.ClientAuth
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.platform.common.service.SourceBridgeService
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.instrument.InstrumentProcessor.removeInternalMeta
import spp.processor.instrument.InstrumentProcessor.sendEventToSubscribers
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.instrument.event.LiveInstrumentAdded
import spp.protocol.instrument.event.LiveInstrumentApplied
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.marshall.ProtocolMarshaller
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.platform.ProbeAddress.LIVE_INSTRUMENT_REMOTE
import spp.protocol.platform.ProcessorAddress
import spp.protocol.platform.ProcessorAddress.REMOTE_REGISTERED
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveInstrumentService
import java.time.Instant
import java.util.*

@Suppress("TooManyFunctions") // public API
class LiveInstrumentServiceImpl : CoroutineVerticle(), LiveInstrumentService {

    private val log = KotlinLogging.logger {}

    override suspend fun start() {
        log.debug("Starting LiveInstrumentProcessorImpl")

        //load preset instruments
        val livePresets = ClusterConnection.config.getJsonObject("live-presets") ?: JsonObject()
        livePresets.map.keys.forEach {
            val presetName = it
            val preset = livePresets.getJsonObject(presetName)
            if (preset.getString("enabled").toBooleanStrict()) {
                val instruments = preset.getJsonArray("instruments", JsonArray())
                Vertx.currentContext().putLocal("developer", DeveloperAuth("system"))
                instruments.forEach {
                    val instrument = LiveInstrument.fromJson(JsonObject.mapFrom(it))
                    addLiveInstrument(instrument).await()
                }
                Vertx.currentContext().removeLocal("developer")
                if (instruments.size() > 0) {
                    log.info { "Loaded ${instruments.size()} live instruments from preset '$presetName'" }
                }
            }
        }

        //send active instruments on probe connection
        vertx.eventBus().consumer<JsonObject>(REMOTE_REGISTERED) {
            val remote = it.body().getString("address").substringBefore(":")
            if (remote == LIVE_INSTRUMENT_REMOTE) {
                log.debug { "Live instrument remote registered. Sending active live instruments" }
                launch(vertx.dispatcher()) {
                    val clientAuth = it.headers().get("client_auth")?.let { ClientAuth.from(it) }
                    val tenantId = clientAuth?.tenantId
                    if (tenantId != null) {
                        Vertx.currentContext().putLocal("tenant_id", tenantId)
                    } else {
                        Vertx.currentContext().removeLocal("tenant_id")
                    }

                    val bootInstruments = SourceStorage.getLiveInstruments()
                    val bootCommand = LiveInstrumentCommand(
                        CommandType.ADD_LIVE_INSTRUMENT,
                        bootInstruments.mapNotNull { removeInternalMeta(it) }.toSet()
                    )

                    dispatchCommand(SourceStorage.getSystemAccessToken(vertx), bootCommand, true)
                }
            }
        }

        //listen for instruments applied/removed
        vertx.eventBus().consumer(ProcessorAddress.LIVE_INSTRUMENT_APPLIED) {
            launch(vertx.dispatcher()) { handleLiveInstrumentApplied(it) }
        }
        vertx.eventBus().consumer(ProcessorAddress.LIVE_INSTRUMENT_REMOVED) {
            launch(vertx.dispatcher()) { handleInstrumentRemoved(it) }
        }
    }

    override fun addLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument> {
        return addLiveInstrument(Vertx.currentContext().getLocal("developer"), instrument)
    }

    private fun addLiveInstrument(devAuth: DeveloperAuth, instrument: LiveInstrument): Future<LiveInstrument> {
        log.info("Received add live instrument request. Developer: {} - Location: {}", devAuth, instrument.location)
        if (instrument is LiveMeter && instrument.isInvalidId()) {
            return Future.failedFuture("Invalid meter id: ${instrument.id}")
        }

        try {
            val promise = Promise.promise<LiveInstrument>()
            when (instrument) {
                is LiveBreakpoint -> {
                    val pendingBp = setupInstrument(devAuth, instrument)
                    if (pendingBp.applyImmediately) {
                        addApplyImmediatelyHandler(pendingBp.id!!, promise).onSuccess {
                            doAddLiveInstrument(pendingBp)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        doAddLiveInstrument(pendingBp).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                is LiveLog -> {
                    val pendingLog = setupInstrument(devAuth, instrument)
                    if (pendingLog.applyImmediately) {
                        addApplyImmediatelyHandler(pendingLog.id!!, promise).onSuccess {
                            doAddLiveInstrument(pendingLog)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        doAddLiveInstrument(pendingLog).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                is LiveMeter -> {
                    val pendingMeter = setupInstrument(devAuth, instrument)
                    if (pendingMeter.applyImmediately) {
                        addApplyImmediatelyHandler(pendingMeter.id!!, promise).onSuccess {
                            doAddLiveInstrument(pendingMeter)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        doAddLiveInstrument(pendingMeter).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                is LiveSpan -> {
                    val pendingSpan = setupInstrument(devAuth, instrument)
                    if (pendingSpan.applyImmediately) {
                        addApplyImmediatelyHandler(pendingSpan.id!!, promise).onSuccess {
                            doAddLiveInstrument(pendingSpan)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        doAddLiveInstrument(pendingSpan).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                else -> promise.fail("Unknown live instrument type")
            }
            return promise.future()
        } catch (throwable: Throwable) {
            log.warn("Add live instrument failed", throwable)
            return Future.failedFuture(throwable)
        }
    }

    private fun setupInstrument(devAuth: DeveloperAuth, instrument: LiveInstrument): LiveInstrument {
        return instrument.copy(
            id = if (instrument.id == null) generateInstrumentId(instrument) else instrument.id,
            pending = true,
            applied = false,
            meta = instrument.meta.toMutableMap().apply {
                put("created_at", System.currentTimeMillis().toString())
                put("created_by", devAuth.selfId)
                put("hit_count", 0)
                put("spp.developer_id", devAuth.selfId)
                devAuth.accessToken?.let { put("spp.access_token", it) }
            }
        )
    }

    private fun generateInstrumentId(instrument: LiveInstrument): String {
        return (if (instrument is LiveMeter) "spp_" else "") +
                UUID.randomUUID().toString().replace("-", "")
    }

    override fun addLiveInstruments(instruments: List<LiveInstrument>): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info(
            "Received add live instrument batch request. Developer: {} - Location(s): {}",
            devAuth, instruments.map { it.location }
        )

        val results = mutableListOf<Future<*>>()
        instruments.forEach {
            results.add(addLiveInstrument(devAuth, it)) //todo: send as batch
        }

        val promise = Promise.promise<List<LiveInstrument>>()
        Future.all(results).onComplete {
            if (it.succeeded()) {
                promise.complete(it.result().list())
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    override fun getLiveInstruments(type: LiveInstrumentType?): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments request. Developer: {}", devAuth)

        val promise = Promise.promise<List<LiveInstrument>>()
        launch(vertx.dispatcher()) {
            promise.complete(
                SourceStorage.getLiveInstruments()
                    .mapNotNull { removeInternalMeta(it) }
                    .filter { type == null || it.type == type }
            )
        }
        return promise.future()
    }

    override fun removeLiveInstrument(id: String): Future<LiveInstrument?> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received remove live instrument request. Developer: {} - Id: {}", devAuth, id)

        val promise = Promise.promise<LiveInstrument?>()
        launch(vertx.dispatcher()) {
            val instrumentRemoval = SourceStorage.getLiveInstrument(id)
            if (instrumentRemoval == null) {
                promise.complete(null)
            } else {
                removeLiveInstrument(Instant.now(), instrumentRemoval, null)
                promise.complete(removeInternalMeta(instrumentRemoval))
            }
        }
        return promise.future()
    }

    override fun removeLiveInstruments(location: LiveSourceLocation): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received remove live instruments request. Developer: {} - Location: {}", devAuth, location)

        val promise = Promise.promise<List<LiveInstrument>>()
        launch(vertx.dispatcher()) {
            val breakpointsResult = removeInstruments(devAuth, location, LiveInstrumentType.BREAKPOINT)
            val logsResult = removeInstruments(devAuth, location, LiveInstrumentType.LOG)
            val metersResult = removeInstruments(devAuth, location, LiveInstrumentType.METER)
            val spansResult = removeInstruments(devAuth, location, LiveInstrumentType.SPAN)

            Future.all(breakpointsResult, logsResult, metersResult, spansResult).onComplete {
                if (it.succeeded()) {
                    promise.complete(
                        it.result().list<List<LiveInstrument>>().flatten().mapNotNull { removeInternalMeta(it) }
                    )
                } else {
                    promise.fail(it.cause())
                }
            }
        }
        return promise.future()
    }

    override fun getLiveInstrument(id: String, includeArchive: Boolean): Future<LiveInstrument?> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instrument by id request. Developer: {} - Id: {}", devAuth, id)

        val promise = Promise.promise<LiveInstrument?>()
        launch(vertx.dispatcher()) {
            promise.complete(removeInternalMeta(SourceStorage.getLiveInstrument(id, includeArchive)))
        }
        return promise.future()
    }

    override fun getLiveInstrumentsByIds(ids: List<String>): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments by ids request. Developer: {} - Ids: {}", devAuth, ids)

        val promise = Promise.promise<List<LiveInstrument>>()
        launch(vertx.dispatcher()) {
            promise.complete(ids.mapNotNull { removeInternalMeta(SourceStorage.getLiveInstrument(it)) })
        }
        return promise.future()
    }

    override fun getLiveInstrumentsByLocation(location: LiveSourceLocation): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments by location request. Developer: {} - Location: {}", devAuth, location)

        val promise = Promise.promise<List<LiveInstrument>>()
        launch(vertx.dispatcher()) {
            promise.complete(
                SourceStorage.getLiveInstruments()
                    .filter { it.location.isSameLocation(location) }
                    .mapNotNull { removeInternalMeta(it) }
            )
        }
        return promise.future()
    }

    override fun getLiveInstrumentEvents(
        instrumentIds: List<String>,
        from: Instant?,
        to: Instant?,
        offset: Int,
        limit: Int
    ): Future<List<LiveInstrumentEvent>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info(
            buildString {
                append("Received get live instrument events request. ")
                append("Developer: {} - ")
                append("Instrument ids: {} - ")
                append("From: {} - ")
                append("To: {} - ")
                append("Offset: {} - ")
                append("Limit: {}")
            }, devAuth, instrumentIds, from, to, offset, limit
        )

        val promise = Promise.promise<List<LiveInstrumentEvent>>()
        launch(vertx.dispatcher()) {
            val events = mutableListOf<LiveInstrumentEvent>()
            instrumentIds.forEach { instrumentId ->
                events.addAll(SourceStorage.getLiveInstrumentEvents(instrumentId, from, to, offset, limit)
                    .map { it.withInstrument(removeInternalMeta(it.instrument)!!) })
            }
            promise.complete(events)
        }
        return promise.future()
    }

    override fun clearAllLiveInstruments(type: LiveInstrumentType?): Future<Boolean> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received clear live instruments request. Developer: {}", devAuth)

        //todo: impl probe clear command
        val promise = Promise.promise<Boolean>()
        launch(vertx.dispatcher()) {
            val allLiveInstruments = getLiveInstruments(type).await()
            allLiveInstruments.mapNotNull { SourceStorage.getLiveInstrument(it.id!!) }.forEach {
                removeLiveInstrument(Instant.now(), it, null)
            }
            promise.complete(true)
        }
        return promise.future()
    }

    override fun clearLiveInstruments(type: LiveInstrumentType?): Future<Boolean> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received clear live instruments request. Developer: {}", devAuth.selfId)

        //todo: impl probe clear command
        val promise = Promise.promise<Boolean>()
        launch(vertx.dispatcher()) {
            val devInstruments = SourceStorage.getLiveInstruments().filter {
                it.meta["spp.developer_id"] == devAuth.selfId && (type == null || it.type == type)
            }
            devInstruments.mapNotNull { SourceStorage.getLiveInstrument(it.id!!) }.forEach {
                removeLiveInstrument(Instant.now(), it, null)
            }
            promise.complete(true)
        }
        return promise.future()
    }

    private suspend fun handleInstrumentRemoved(it: Message<JsonObject>) {
        log.trace { "Got live instrument removed: {}".args(it.body()) }
        val instrumentCommand = it.body().getString("command")
        val instrumentData = if (instrumentCommand != null) {
            val command = LiveInstrumentCommand(JsonObject(instrumentCommand))
            if (command.instruments.isEmpty()) {
                log.warn("Got empty instrument removed command: {}", it.body())
                return
            } else {
                JsonObject.mapFrom(command.instruments.first()) //todo: check for multiple, add locations
            }
        } else if (it.body().containsKey("instrument")) {
            JsonObject(it.body().getString("instrument"))
        } else {
            throw IllegalArgumentException("Unknown instrument removed message: $it")
        }

        val instrumentRemoval = SourceStorage.getLiveInstrument(instrumentData.getString("id"))
        if (instrumentRemoval != null) {
            //publish remove command to all probes & markers
            removeLiveInstrument(
                Instant.ofEpochMilli(it.body().getLong("occurredAt")),
                instrumentRemoval,
                it.body().getString("cause")
            )
        }
    }

    private suspend fun handleLiveInstrumentApplied(it: Message<JsonObject>) {
        var instrument = SourceStorage.getLiveInstrument(
            ProtocolMarshaller.deserializeLiveInstrument(it.body()).id!!, true
        )
        if (instrument == null) {
            log.warn { "Got live instrument applied for unknown instrument: {}".args(it.body()) }
            return
        }
        log.info("Live instrument applied. Id: {}", instrument.id)

        instrument = when (instrument.type) {
            LiveInstrumentType.BREAKPOINT -> {
                (instrument as LiveBreakpoint).copy(
                    applied = true,
                    pending = false
                )
            }

            LiveInstrumentType.LOG -> {
                (instrument as LiveLog).copy(
                    applied = true,
                    pending = false
                )
            }

            LiveInstrumentType.METER -> {
                (instrument as LiveMeter).copy(
                    applied = true,
                    pending = false
                )
            }

            LiveInstrumentType.SPAN -> {
                (instrument as LiveSpan).copy(
                    applied = true,
                    pending = false
                )
            }

            else -> throw IllegalArgumentException("Unknown live instrument type")
        }

        val appliedAt = Instant.now() //todo: get from probe
        (instrument.meta as MutableMap<String, Any>)["applied_at"] = "${appliedAt.toEpochMilli()}"
        SourceStorage.updateLiveInstrument(instrument.id!!, instrument)

        vertx.eventBus().send("apply-immediately.${instrument.id}", instrument)

        sendEventToSubscribers(instrument, LiveInstrumentApplied(removeInternalMeta(instrument)!!, appliedAt))
        log.trace { "Published live instrument applied" }
    }

    private fun addApplyImmediatelyHandler(
        instrumentId: String,
        handler: Handler<AsyncResult<LiveInstrument>>
    ): Future<Void> {
        val promise = Promise.promise<Void>()
        val consumer = vertx.eventBus().consumer<Any>("apply-immediately.$instrumentId")
        consumer.handler {
            if (it.body() is LiveInstrument) {
                val instrument = it.body() as LiveInstrument
                handler.handle(Future.succeededFuture(removeInternalMeta(instrument)))
                consumer.unregister()
            } else if (it.body() is ServiceException) {
                val exception = it.body() as ServiceException
                handler.handle(Future.failedFuture(exception))
                consumer.unregister()
            }
            it.reply(true)
        }.exceptionHandler {
            handler.handle(Future.failedFuture(it))
            consumer.unregister()
        }.completionHandler {
            if (it.succeeded()) {
                promise.complete()
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    private fun doAddLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument> {
        log.trace { "Adding live instrument: {}".args(instrument) }
        val promise = Promise.promise<LiveInstrument>()
        launch(vertx.dispatcher()) {
            val publicInstrument = removeInternalMeta(instrument)!!
            val debuggerCommand = LiveInstrumentCommand(
                CommandType.ADD_LIVE_INSTRUMENT,
                setOf(publicInstrument)
            )

            val accessToken = instrument.meta["spp.access_token"] as String?
            SourceStorage.addLiveInstrument(instrument)
            dispatchCommand(accessToken, debuggerCommand)

            sendEventToSubscribers(instrument, LiveInstrumentAdded(publicInstrument))

            promise.complete(publicInstrument)
        }
        return promise.future()
    }

    private fun dispatchCommand(accessToken: String?, command: LiveInstrumentCommand, forceDispatch: Boolean = false) {
        log.trace { "Dispatching {}. Using access token: {}".args(command, accessToken) }
        SourceBridgeService.createProxy(vertx, accessToken).onSuccess {
            if (it == null) {
                log.error("Bridge service not available")
                return@onSuccess
            }

            it.getActiveProbes().onSuccess {
                val alertProbes = it.list.map { InstanceConnection(JsonObject.mapFrom(it)) }
                if (alertProbes.isEmpty()) {
                    log.warn("No probes connected. Unable to dispatch $command")
                    return@onSuccess
                }

                var alertCount = 0
                log.debug { "Dispatching {}. Found {} connected probe(s)".args(command, alertProbes.size) }
                alertProbes.forEach { probe ->
                    val probeCommand = LiveInstrumentCommand(
                        command.commandType,
                        command.instruments.filter { it.location.isSameLocation(probe) }.toSet(),
                        command.locations.filter { it.isSameLocation(probe) }.toSet()
                    )
                    if (probeCommand.isDispatchable() || forceDispatch) {
                        alertCount++
                        vertx.eventBus().publish(LIVE_INSTRUMENT_REMOTE + ":" + probe.instanceId, probeCommand.toJson())
                        log.debug { "Dispatched $probeCommand to probe ${probe.instanceId}" }
                    }
                }
                log.info { "Dispatched {} to {} of {} connected probe(s)".args(command, alertCount, alertProbes.size) }
            }.onFailure {
                log.error("Failed to get active probes", it)
            }
        }.onFailure {
            log.error("Failed to get bridge service", it)
        }
    }

    private suspend fun removeLiveInstrument(occurredAt: Instant, instrument: LiveInstrument, cause: String?) {
        log.debug { "Removing live instrument: {} with cause: {}".args(instrument, cause) }
        SourceStorage.removeLiveInstrument(instrument.id!!)

        val accessToken = instrument.meta["spp.access_token"] as String?
        val debuggerCommand = LiveInstrumentCommand(
            CommandType.REMOVE_LIVE_INSTRUMENT,
            setOf(removeInternalMeta(instrument)!!)
        )
        dispatchCommand(accessToken, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        if (cause?.startsWith("EventBusException") == true) {
            val ebException = ServiceExceptionConverter.fromEventBusException(cause, true)
            vertx.eventBus().send("apply-immediately.${instrument.id}", ebException)
        }

        val removedEvent = LiveInstrumentRemoved(removeInternalMeta(instrument)!!, occurredAt, jvmCause)
        sendEventToSubscribers(instrument, removedEvent)

        if (jvmCause != null) {
            log.warn("Publish live instrument removed. Cause: {} - {}", jvmCause.exceptionType, jvmCause.message)
        } else {
            log.info("Published live instrument removed")
        }
    }

    private suspend fun removeInstruments(
        devAuth: DeveloperAuth,
        location: LiveSourceLocation,
        instrumentType: LiveInstrumentType
    ): Future<List<LiveInstrument>> {
        log.debug { "Removing live instrument(s): {}".args(location) }
        val debuggerCommand = LiveInstrumentCommand(CommandType.REMOVE_LIVE_INSTRUMENT, locations = setOf(location))

        val result = SourceStorage.getLiveInstruments().filter {
            location.isSameLocation(it.location) && it.type == instrumentType
        }
        result.toSet().forEach { SourceStorage.removeLiveInstrument(it.id!!) }
        if (result.isEmpty()) {
            log.info("Could not find live instrument(s) at: $location")
        } else {
            dispatchCommand(devAuth.accessToken, debuggerCommand)
            log.debug { "Removed live instrument(s) at: {}".args(location) }

            result.forEach {
                sendEventToSubscribers(it, LiveInstrumentRemoved(removeInternalMeta(it)!!, Instant.now()))
            }
        }
        return Future.succeededFuture(result.filter { it.type == instrumentType }.map { it })
    }
}
