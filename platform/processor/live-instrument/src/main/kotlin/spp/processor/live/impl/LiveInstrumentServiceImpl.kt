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
package spp.processor.live.impl

import io.vertx.core.*
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.serviceproxy.ServiceException
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.skywalking.oap.meter.analyzer.Analyzer
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.joor.Reflect
import spp.platform.common.ClientAuth
import spp.platform.common.DeveloperAuth
import spp.platform.common.FeedbackProcessor
import spp.platform.common.service.SourceBridgeService
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.InstrumentProcessor.removeInternalMeta
import spp.processor.InstrumentProcessor.sendEventToSubscribers
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.instrument.event.LiveInstrumentAdded
import spp.protocol.instrument.event.LiveInstrumentApplied
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

class LiveInstrumentServiceImpl : CoroutineVerticle(), LiveInstrumentService {

    private val log = KotlinLogging.logger {}
    private lateinit var meterSystem: MeterSystem
    private lateinit var meterProcessService: MeterProcessService

    override suspend fun start() {
        log.debug("Starting LiveInstrumentProcessorImpl")
        FeedbackProcessor.module!!.find(CoreModule.NAME).provider().apply {
            meterSystem = getService(MeterSystem::class.java)
        }
        FeedbackProcessor.module!!.find(AnalyzerModule.NAME).provider().apply {
            meterProcessService = getService(IMeterProcessService::class.java) as MeterProcessService
        }

        //send active instruments on probe connection
        vertx.eventBus().consumer<JsonObject>(REMOTE_REGISTERED) {
            //todo: impl batch instrument add
            //todo: more efficient to just send batch add to specific probe instead of publish to all per connection
            //todo: probably need to redo pending boolean. it doesn't make sense here since pending just means
            // it has been applied to any instrument at all at any point
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

                    SourceStorage.getLiveInstruments().forEach {
                        addLiveInstrument(it, false)
                    }
                }
            }
        }

        //listen for instruments applied/removed
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LIVE_INSTRUMENT_APPLIED) {
            launch(vertx.dispatcher()) { handleLiveInstrumentApplied(it) }
        }
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LIVE_INSTRUMENT_REMOVED) {
            launch(vertx.dispatcher()) { handleInstrumentRemoved(it) }
        }
    }

    override fun addLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument> {
        return addLiveInstrument(Vertx.currentContext().getLocal("developer"), instrument)
    }

    private fun addLiveInstrument(devAuth: DeveloperAuth, instrument: LiveInstrument): Future<LiveInstrument> {
        log.info(
            "Received add live instrument request. Developer: {} - Location: {}",
            devAuth, instrument.location.let { it.source + ":" + it.line }
        )

        try {
            val promise = Promise.promise<LiveInstrument>()
            when (instrument) {
                is LiveBreakpoint -> {
                    val pendingBp = if (instrument.id == null) {
                        instrument.copy(id = UUID.randomUUID().toString())
                    } else {
                        instrument
                    }.copy(
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

                    if (pendingBp.applyImmediately) {
                        addApplyImmediatelyHandler(pendingBp.id!!, promise).onSuccess {
                            addLiveInstrument(pendingBp, true)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        addLiveInstrument(pendingBp, true).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                is LiveLog -> {
                    val pendingLog = if (instrument.id == null) {
                        instrument.copy(id = UUID.randomUUID().toString())
                    } else {
                        instrument
                    }.copy(
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

                    if (pendingLog.applyImmediately) {
                        addApplyImmediatelyHandler(pendingLog.id!!, promise).onSuccess {
                            addLiveInstrument(pendingLog, true)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        addLiveInstrument(pendingLog, true).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                is LiveMeter -> {
                    val pendingMeter = if (instrument.id == null) {
                        instrument.copy(id = UUID.randomUUID().toString())
                    } else {
                        instrument
                    }.copy(
                        pending = true,
                        applied = false,
                        meta = instrument.meta.toMutableMap().apply {
                            put("created_at", System.currentTimeMillis().toString())
                            put("created_by", devAuth.selfId)
                            put("spp.developer_id", devAuth.selfId)
                            devAuth.accessToken?.let { put("spp.access_token", it) }
                        }
                    )

                    if (pendingMeter.applyImmediately) {
                        addApplyImmediatelyHandler(pendingMeter.id!!, promise).onSuccess {
                            addLiveInstrument(pendingMeter, true)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        addLiveInstrument(pendingMeter, true).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                is LiveSpan -> {
                    val pendingSpan = if (instrument.id == null) {
                        instrument.copy(id = UUID.randomUUID().toString())
                    } else {
                        instrument
                    }.copy(
                        pending = true,
                        applied = false,
                        meta = instrument.meta.toMutableMap().apply {
                            put("created_at", System.currentTimeMillis().toString())
                            put("created_by", devAuth.selfId)
                            put("spp.developer_id", devAuth.selfId)
                            devAuth.accessToken?.let { put("spp.access_token", it) }
                        }
                    )

                    if (pendingSpan.applyImmediately) {
                        addApplyImmediatelyHandler(pendingSpan.id!!, promise).onSuccess {
                            addLiveInstrument(pendingSpan, true)
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        addLiveInstrument(pendingSpan, true).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                else -> {
                    promise.fail("Unknown live instrument type")
                }
            }
            return promise.future()
        } catch (throwable: Throwable) {
            log.warn("Add live instrument failed", throwable)
            return Future.failedFuture(throwable)
        }
    }

    override fun addLiveInstruments(instruments: List<LiveInstrument>): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info(
            "Received add live instrument batch request. Developer: {} - Location(s): {}",
            devAuth, instruments.map { it.location.let { it.source + ":" + it.line } }
        )

        val results = mutableListOf<Future<*>>()
        instruments.forEach {
            results.add(addLiveInstrument(devAuth, it)) //todo: send as batch
        }

        val promise = Promise.promise<List<LiveInstrument>>()
        CompositeFuture.all(results).onComplete {
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
                removeLiveInstrument(instrumentRemoval).onSuccess {
                    promise.complete(removeInternalMeta(it))
                }.onFailure {
                    promise.fail(it)
                }
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

            CompositeFuture.all(breakpointsResult, logsResult, metersResult, spansResult).onComplete {
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

    override fun getLiveInstrumentById(id: String): Future<LiveInstrument?> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instrument by id request. Developer: {} - Id: {}", devAuth, id)

        val promise = Promise.promise<LiveInstrument?>()
        launch(vertx.dispatcher()) {
            promise.complete(removeInternalMeta(SourceStorage.getLiveInstrument(id)))
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

    override fun clearAllLiveInstruments(type: LiveInstrumentType?): Future<Boolean> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received clear live instruments request. Developer: {}", devAuth)

        //todo: impl probe clear command
        val promise = Promise.promise<Boolean>()
        launch(vertx.dispatcher()) {
            val allLiveInstruments = getLiveInstruments(type).await()
            allLiveInstruments.mapNotNull { SourceStorage.getLiveInstrument(it.id!!) }.forEach {
                removeLiveInstrument(it)
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
                removeLiveInstrument(it)
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
            JsonObject.mapFrom(command.instruments.first()) //todo: check for multiple
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
            } else if (it.body() is ServiceException) {
                val exception = it.body() as ServiceException
                handler.handle(Future.failedFuture(exception))
            } else {
                handler.handle(Future.failedFuture("Live instrument was removed"))
            }
            consumer.unregister()
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

    private fun addLiveInstrument(instrument: LiveInstrument, alertSubscribers: Boolean): Future<LiveInstrument> {
        log.trace { "Adding live instrument: {}".args(instrument) }
        val promise = Promise.promise<LiveInstrument>()
        launch(vertx.dispatcher()) {
            val debuggerCommand = LiveInstrumentCommand(
                CommandType.ADD_LIVE_INSTRUMENT,
                setOf(removeInternalMeta(instrument)!!)
            )

            val accessToken = instrument.meta["spp.access_token"] as String?
            SourceStorage.addLiveInstrument(instrument)
            dispatchCommand(accessToken, LIVE_INSTRUMENT_REMOTE, debuggerCommand)

            if (alertSubscribers) {
                sendEventToSubscribers(instrument, LiveInstrumentAdded(removeInternalMeta(instrument)!!))
            }
            promise.complete(removeInternalMeta(instrument))
        }
        return promise.future()
    }

    private fun dispatchCommand(accessToken: String?, address: String, command: LiveInstrumentCommand) {
        log.trace { "Dispatching command: {}. Using access token: {}".args(command, accessToken) }
        val probes = SourceBridgeService.service(vertx, accessToken)
        probes.onSuccess {
            if (it == null) {
                log.error("Bridge service not available")
                return@onSuccess
            }

            it.getActiveProbes().onSuccess {
                val alertProbes = it.list.map { InstanceConnection(JsonObject.mapFrom(it)) }
                if (alertProbes.isEmpty()) {
                    log.warn("No probes connected. Unable to dispatch {} command", command.commandType)
                    return@onSuccess
                }

                log.debug {
                    "Dispatching command {} to {} connected probe(s)".args(command.commandType, alertProbes.size)
                }
                alertProbes.forEach { probe ->
                    val probeCommand = LiveInstrumentCommand(
                        command.commandType,
                        command.instruments.filter { it.location.isSameLocation(probe) }.toSet(),
                        command.locations.filter { it.isSameLocation(probe) }.toSet()
                    )
                    if (probeCommand.instruments.isNotEmpty() || probeCommand.locations.isNotEmpty()) {
                        vertx.eventBus().publish(
                            address + ":" + probe.instanceId,
                            JsonObject.mapFrom(probeCommand)
                        )
                        log.debug { "Dispatched command ${probeCommand.commandType} to probe ${probe.instanceId}" }
                    } else {
                        log.debug { "No instruments/locations to dispatch to probe ${probe.instanceId}" }
                    }
                }
            }.onFailure {
                log.error("Failed to get active probes", it)
            }
        }.onFailure {
            log.error("Failed to get bridge service", it)
        }
    }

    private suspend fun removeLiveInstrument(occurredAt: Instant, instrument: LiveInstrument, cause: String?) {
        log.debug { "Removing live instrument: {}".args(instrument.id) }
        SourceStorage.removeLiveInstrument(instrument.id!!)

        val accessToken = instrument.meta["spp.access_token"] as String?
        val debuggerCommand = LiveInstrumentCommand(
            CommandType.REMOVE_LIVE_INSTRUMENT,
            setOf(removeInternalMeta(instrument)!!)
        )
        dispatchCommand(accessToken, LIVE_INSTRUMENT_REMOTE, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        val ebException = if (cause?.startsWith("EventBusException") == true) {
            ServiceExceptionConverter.fromEventBusException(cause, true)
        } else null
        vertx.eventBus().request<Void>("apply-immediately.${instrument.id}", ebException).onFailure {
            launch(vertx.dispatcher()) {
                val removedEvent = LiveInstrumentRemoved(removeInternalMeta(instrument)!!, occurredAt, jvmCause)
                sendEventToSubscribers(instrument, removedEvent)
            }
        }

        if (jvmCause != null) {
            log.warn("Publish live instrument removed. Cause: {} - {}", jvmCause.exceptionType, jvmCause.message)
        } else {
            log.info("Published live instrument removed")
        }
    }

    private suspend fun removeLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument?> {
        //if live meter, also remove from SkyWalking meter process service
        if (instrument is LiveMeter) {
            meterProcessService.converts().removeIf {
                val analyzers = Reflect.on(it).field("analyzers").get<ArrayList<Analyzer>>()
                analyzers.removeIf {
                    val metricName = Reflect.on(it).field("metricName").get<String>()
                    metricName == instrument.toMetricId()
                }

                analyzers.isEmpty()
            }
        }

        //publish remove command to all probes
        removeLiveInstrument(Instant.now(), instrument, null)
        return Future.succeededFuture(instrument)
    }

    private suspend fun removeInstruments(
        devAuth: DeveloperAuth,
        location: LiveSourceLocation,
        instrumentType: LiveInstrumentType
    ): Future<List<LiveInstrument>> {
        log.debug { "Removing live instrument(s): {}".args(location) }
        val debuggerCommand = LiveInstrumentCommand(CommandType.REMOVE_LIVE_INSTRUMENT, locations = setOf(location))

        val result = SourceStorage.getLiveInstruments().filter {
            it.location.isSameLocation(location) && it.type == instrumentType
        }
        result.toSet().forEach { SourceStorage.removeLiveInstrument(it.id!!) }
        if (result.isEmpty()) {
            log.info("Could not find live instrument(s) at: $location")
        } else {
            dispatchCommand(devAuth.accessToken, LIVE_INSTRUMENT_REMOTE, debuggerCommand)
            log.debug { "Removed live instrument(s) at: {}".args(location) }

            result.forEach {
                sendEventToSubscribers(it, LiveInstrumentRemoved(removeInternalMeta(it)!!, Instant.now()))
            }
        }
        return Future.succeededFuture(result.filter { it.type == instrumentType }.map { it })
    }
}
