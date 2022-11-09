/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
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
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.skywalking.oap.meter.analyzer.Analyzer
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.joor.Reflect
import spp.platform.common.DeveloperAuth
import spp.platform.common.FeedbackProcessor
import spp.platform.common.service.SourceBridgeService
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.live.impl.instrument.meter.LiveMeterRule
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.marshall.ProtocolMarshaller
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.platform.ProbeAddress.LIVE_INSTRUMENT_REMOTE
import spp.protocol.platform.ProcessorAddress
import spp.protocol.platform.ProcessorAddress.REMOTE_REGISTERED
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class LiveInstrumentServiceImpl : CoroutineVerticle(), LiveInstrumentService {

    companion object {
        private val log = KotlinLogging.logger {}
        const val METRIC_PREFIX = "spp"
    }

    private lateinit var meterSystem: MeterSystem
    private lateinit var meterProcessService: MeterProcessService

    private val waitingApply = ConcurrentHashMap<String, Handler<AsyncResult<LiveInstrument>>>()

    override suspend fun start() {
        log.info("Starting LiveInstrumentProcessorImpl")
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
                    SourceStorage.getLiveInstruments().forEach {
                        _addLiveInstrument(it, false)
                    }
                }
            }
        }

        //listen for instruments applied/removed
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LIVE_INSTRUMENT_APPLIED) {
            launch(vertx.dispatcher()) {
                handleLiveInstrumentApplied(it)
            }
        }
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LIVE_INSTRUMENT_REMOVED) {
            launch(vertx.dispatcher()) {
                handleInstrumentRemoved(it)
            }
        }
    }

    override fun addLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        return addLiveInstrument(devAuth, instrument)
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
                        addApplyImmediatelyHandler(pendingBp.id!!, promise)
                        _addLiveInstrument(pendingBp)
                    } else {
                        _addLiveInstrument(pendingBp).onComplete {
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
                        addApplyImmediatelyHandler(pendingLog.id!!, promise)
                        _addLiveInstrument(pendingLog)
                    } else {
                        _addLiveInstrument(pendingLog).onComplete {
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

                    //save live meter to SkyWalking meter process service
                    LiveMeterRule.toMeterConfig(pendingMeter)?.let {
                        meterProcessService.converts().add(MetricConvert(it, meterSystem))
                    }

                    if (pendingMeter.applyImmediately) {
                        addApplyImmediatelyHandler(pendingMeter.id!!, promise)
                        _addLiveInstrument(pendingMeter)
                    } else {
                        _addLiveInstrument(pendingMeter).onComplete {
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
                        addApplyImmediatelyHandler(pendingSpan.id!!, promise)
                        _addLiveInstrument(pendingSpan)
                    } else {
                        _addLiveInstrument(pendingSpan).onComplete {
                            if (it.succeeded()) {
                                promise.complete(it.result())
                            } else {
                                promise.fail(it.cause())
                            }
                        }
                    }
                }

                else -> {
                    promise.fail(IllegalArgumentException("Unknown live instrument type"))
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
            results.add(addLiveInstrument(devAuth, it))
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
            promise.complete(SourceStorage.getLiveInstruments().filter { type == null || it.type == type })
        }
        return promise.future()
    }

    override fun removeLiveInstrument(id: String): Future<LiveInstrument?> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received remove live instrument request. Developer: {} - Id: {}", devAuth, id)

        val promise = Promise.promise<LiveInstrument?>()
        launch(vertx.dispatcher()) {
            removeLiveInstrument(devAuth, id).onSuccess {
                promise.complete(it)
            }.onFailure {
                promise.fail(it)
            }
        }
        return promise.future()
    }

    override fun removeLiveInstruments(location: LiveSourceLocation): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received remove live instruments request. Developer: {} - Location: {}", devAuth, location)

        try {
            val promise = Promise.promise<List<LiveInstrument>>()
            launch(vertx.dispatcher()) {
                val breakpointsResult = removeInstruments(devAuth, location, LiveInstrumentType.BREAKPOINT)
                val logsResult = removeInstruments(devAuth, location, LiveInstrumentType.LOG)
                val metersResult = removeInstruments(devAuth, location, LiveInstrumentType.METER)
                val spansResult = removeInstruments(devAuth, location, LiveInstrumentType.SPAN)

                CompositeFuture.all(breakpointsResult, logsResult, metersResult, spansResult).onComplete {
                    if (it.succeeded()) {
                        promise.complete(it.result().list<List<LiveInstrument>>().flatten())
                    } else {
                        promise.fail(it.cause())
                    }
                }
            }
            return promise.future()
        } catch (throwable: Throwable) {
            log.warn("Remove live instruments failed", throwable)
            return Future.failedFuture(throwable)
        }
    }

    override fun getLiveInstrumentById(id: String): Future<LiveInstrument?> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instrument by id request. Developer: {} - Id: {}", devAuth, id)

        val promise = Promise.promise<LiveInstrument?>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getLiveInstrument(id))
        }
        return promise.future()
    }

    override fun getLiveInstrumentsByIds(ids: List<String>): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments by ids request. Developer: {} - Ids: {}", devAuth, ids)

        val promise = Promise.promise<List<LiveInstrument>>()
        launch(vertx.dispatcher()) {
            promise.complete(ids.mapNotNull { SourceStorage.getLiveInstrument(it) })
        }
        return promise.future()
    }

    override fun getLiveInstrumentsByLocation(location: LiveSourceLocation): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments by location request. Developer: {} - Location: {}", devAuth, location)

        val promise = Promise.promise<List<LiveInstrument>>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getLiveInstruments().filter { it.location.isSameLocation(location) })
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
            allLiveInstruments.forEach {
                removeLiveInstrument(devAuth, it.id!!)
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
            devInstruments.forEach {
                removeLiveInstrument(devAuth, it.id!!)
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

        val instrumentRemoval = SourceStorage.getLiveInstruments().find {
            it.id == instrumentData.getString("id")
        }
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
        val liveInstrument = ProtocolMarshaller.deserializeLiveInstrument(it.body())
        SourceStorage.getLiveInstruments().forEach {
            if (it.id == liveInstrument.id) {
                log.info("Live instrument applied. Id: {}", it.id)
                val eventType: LiveInstrumentEventType
                val appliedInstrument: LiveInstrument
                when (liveInstrument.type) {
                    LiveInstrumentType.BREAKPOINT -> {
                        eventType = LiveInstrumentEventType.BREAKPOINT_APPLIED
                        appliedInstrument = (it as LiveBreakpoint).copy(
                            applied = true,
                            pending = false
                        )
                    }

                    LiveInstrumentType.LOG -> {
                        eventType = LiveInstrumentEventType.LOG_APPLIED
                        appliedInstrument = (it as LiveLog).copy(
                            applied = true,
                            pending = false
                        )
                    }

                    LiveInstrumentType.METER -> {
                        eventType = LiveInstrumentEventType.METER_APPLIED
                        appliedInstrument = (it as LiveMeter).copy(
                            applied = true,
                            pending = false
                        )
                    }

                    LiveInstrumentType.SPAN -> {
                        eventType = LiveInstrumentEventType.SPAN_APPLIED
                        appliedInstrument = (it as LiveSpan).copy(
                            applied = true,
                            pending = false
                        )
                    }

                    else -> throw IllegalArgumentException("Unknown live instrument type")
                }
                (appliedInstrument.meta as MutableMap<String, Any>)["applied_at"] = "${System.currentTimeMillis()}"

                waitingApply.remove(appliedInstrument.id)?.handle(Future.succeededFuture(appliedInstrument))

                val selfId = it.meta["spp.developer_id"] as String
                vertx.eventBus().publish(
                    toLiveInstrumentSubscriberAddress(selfId),
                    JsonObject.mapFrom(LiveInstrumentEvent(eventType, Json.encode(appliedInstrument)))
                )
                log.trace { "Published live instrument applied" }
                return@forEach
            }
        }
    }

    private fun addApplyImmediatelyHandler(instrumentId: String, handler: Handler<AsyncResult<LiveInstrument>>) {
        waitingApply[instrumentId] = Handler<AsyncResult<LiveInstrument>> {
            if (it.succeeded()) {
                handler.handle(Future.succeededFuture(it.result()))
            } else {
                handler.handle(Future.failedFuture(it.cause()))
            }
        }
    }

    private fun _addLiveInstrument(
        liveInstrument: LiveInstrument,
        alertSubscribers: Boolean = true
    ): Future<LiveInstrument> {
        log.debug { "Adding live instrument: {}".args(liveInstrument) }
        val promise = Promise.promise<LiveInstrument>()
        launch(vertx.dispatcher()) {
            val debuggerCommand = LiveInstrumentCommand(CommandType.ADD_LIVE_INSTRUMENT, setOf(liveInstrument))

            val selfId = liveInstrument.meta["spp.developer_id"] as String
            val accessToken = liveInstrument.meta["spp.access_token"] as String?
            SourceStorage.addLiveInstrument(liveInstrument)
            dispatchCommand(accessToken, LIVE_INSTRUMENT_REMOTE, debuggerCommand)

            if (alertSubscribers) {
                val eventType = when (liveInstrument.type) {
                    LiveInstrumentType.BREAKPOINT -> LiveInstrumentEventType.BREAKPOINT_ADDED
                    LiveInstrumentType.LOG -> LiveInstrumentEventType.LOG_ADDED
                    LiveInstrumentType.METER -> LiveInstrumentEventType.METER_ADDED
                    LiveInstrumentType.SPAN -> LiveInstrumentEventType.SPAN_ADDED
                }
                vertx.eventBus().publish(
                    toLiveInstrumentSubscriberAddress(selfId),
                    JsonObject.mapFrom(LiveInstrumentEvent(eventType, Json.encode(liveInstrument)))
                )
            }
            promise.complete(liveInstrument)
        }
        return promise.future()
    }

    private fun dispatchCommand(accessToken: String?, address: String, debuggerCommand: LiveInstrumentCommand) {
        val probes = SourceBridgeService.service(vertx, accessToken)
        probes.onSuccess {
            if (it == null) {
                log.error("Bridge service not available")
                return@onSuccess
            }

            it.getActiveProbes().onComplete {
                log.trace { "Dispatching command {} to connected probe(s)".args(debuggerCommand.commandType) }
                val alertProbes = it.result().list.map { InstanceConnection(JsonObject.mapFrom(it)) }
                alertProbes.forEach { probe ->
                    val probeCommand = LiveInstrumentCommand(
                        debuggerCommand.commandType,
                        debuggerCommand.instruments.filter { it.location.isSameLocation(probe) }.toSet(),
                        debuggerCommand.locations.filter { it.isSameLocation(probe) }.toSet()
                    )
                    if (probeCommand.instruments.isNotEmpty() || probeCommand.locations.isNotEmpty()) {
                        log.debug { "Dispatching command ${probeCommand.commandType} to probe ${probe.instanceId}" }
                        vertx.eventBus().publish(
                            address + ":" + probe.instanceId,
                            JsonObject.mapFrom(probeCommand)
                        )
                    }
                }
            }.onFailure {
                log.error("Failed to get active probes", it)
            }
        }.onFailure {
            log.error("Failed to get bridge service", it)
        }
    }

    private suspend fun removeLiveInstrument(
        occurredAt: Instant,
        devInstrument: LiveInstrument,
        cause: String?
    ) {
        log.debug { "Removing live instrument: {}".args(devInstrument.id) }
        SourceStorage.removeLiveInstrument(devInstrument.id!!)

        val selfId = devInstrument.meta["spp.developer_id"] as String
        val accessToken = devInstrument.meta["spp.access_token"] as String?
        val debuggerCommand = LiveInstrumentCommand(CommandType.REMOVE_LIVE_INSTRUMENT, setOf(devInstrument))
        dispatchCommand(accessToken, LIVE_INSTRUMENT_REMOTE, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        val waitingHandler = waitingApply.remove(devInstrument.id)
        if (waitingHandler != null) {
            if (cause?.startsWith("EventBusException") == true) {
                val ebException = ServiceExceptionConverter.fromEventBusException(cause, true)
                waitingHandler.handle(Future.failedFuture(ebException))
            } else {
                waitingHandler.handle(Future.failedFuture("Live instrument was removed"))
            }
        } else {
            val eventType = when (devInstrument.type) {
                LiveInstrumentType.BREAKPOINT -> LiveInstrumentEventType.BREAKPOINT_REMOVED
                LiveInstrumentType.LOG -> LiveInstrumentEventType.LOG_REMOVED
                LiveInstrumentType.METER -> LiveInstrumentEventType.METER_REMOVED
                LiveInstrumentType.SPAN -> LiveInstrumentEventType.SPAN_REMOVED
            }
            val eventData = Json.encode(LiveInstrumentRemoved(devInstrument, occurredAt, jvmCause))
            vertx.eventBus().publish(
                toLiveInstrumentSubscriberAddress(selfId),
                JsonObject.mapFrom(LiveInstrumentEvent(eventType, eventData))
            )
        }

        if (jvmCause != null) {
            log.warn("Publish live instrument removed. Cause: {} - {}", jvmCause.exceptionType, jvmCause.message)
        } else {
            log.info("Published live instrument removed")
        }
    }

    private suspend fun removeLiveInstrument(
        developerAuth: DeveloperAuth,
        instrumentId: String
    ): Future<LiveInstrument?> {
        log.trace { "Removing live instrument: {}".args(instrumentId) }
        val instrumentRemoval = SourceStorage.getLiveInstrument(instrumentId)
        return if (instrumentRemoval != null) {
            removeLiveInstrument(developerAuth, instrumentRemoval)
        } else {
            Future.succeededFuture()
        }
    }

    private suspend fun removeLiveInstrument(
        devAuth: DeveloperAuth,
        instrumentRemoval: LiveInstrument
    ): Future<LiveInstrument?> {
        //if live meter, also remove from SkyWalking meter process service
        if (instrumentRemoval is LiveMeter) {
            meterProcessService.converts().removeIf {
                val analyzers = Reflect.on(it).field("analyzers").get<ArrayList<Analyzer>>()
                analyzers.removeIf {
                    val metricName = Reflect.on(it).field("metricName").get<String>()
                    metricName == instrumentRemoval.toMetricId()
                }

                analyzers.isEmpty()
            }
        }

        //publish remove command to all probes
        removeLiveInstrument(Instant.now(), instrumentRemoval, null)
        return Future.succeededFuture(instrumentRemoval)
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

            val eventType = when (instrumentType) {
                LiveInstrumentType.BREAKPOINT -> LiveInstrumentEventType.BREAKPOINT_REMOVED
                LiveInstrumentType.LOG -> LiveInstrumentEventType.LOG_REMOVED
                LiveInstrumentType.METER -> LiveInstrumentEventType.METER_REMOVED
                LiveInstrumentType.SPAN -> LiveInstrumentEventType.SPAN_REMOVED
            }

            val removedArray = JsonArray()
            result.forEach {
                removedArray.add(JsonObject.mapFrom(LiveInstrumentRemoved(it, Instant.now(), null)))
            }
            val eventData = Json.encode(removedArray)
            vertx.eventBus().publish(
                toLiveInstrumentSubscriberAddress(devAuth.selfId),
                JsonObject.mapFrom(LiveInstrumentEvent(eventType, eventData))
            )
        }
        return Future.succeededFuture(result.filter { it.type == instrumentType }.map { it })
    }
}
