/*
 * Source++, the open-source live coding platform.
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

import com.google.common.cache.CacheBuilder
import io.vertx.core.*
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import mu.KotlinLogging
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.apache.skywalking.oap.server.core.query.MetricsQueryService
import org.apache.skywalking.oap.server.core.query.enumeration.Scope
import org.apache.skywalking.oap.server.core.query.enumeration.Step
import org.apache.skywalking.oap.server.core.query.input.Duration
import org.apache.skywalking.oap.server.core.query.input.Entity
import org.apache.skywalking.oap.server.core.query.input.MetricsCondition
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.core.storage.query.IMetadataQueryDAO
import org.apache.skywalking.oap.server.core.version.Version
import org.joor.Reflect
import spp.platform.common.DeveloperAuth
import spp.platform.common.FeedbackProcessor
import spp.platform.common.SkyWalkingStorage.Companion.METRIC_PREFIX
import spp.platform.common.extend.getMeterServiceInstances
import spp.platform.common.extend.getMeterServices
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.instrument.*
import spp.protocol.instrument.command.CommandType
import spp.protocol.instrument.command.LiveInstrumentCommand
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.instrument.meter.MeterType
import spp.protocol.marshall.ProtocolMarshaller
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.platform.ProbeAddress.LIVE_INSTRUMENT_REMOTE
import spp.protocol.platform.ProcessorAddress
import spp.protocol.platform.ProcessorAddress.REMOTE_REGISTERED
import spp.protocol.service.LiveInstrumentService
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveInstrumentProcessorImpl : CoroutineVerticle(), LiveInstrumentService {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private lateinit var metricsQueryService: MetricsQueryService
    private lateinit var metadata: IMetadataQueryDAO
    private lateinit var meterSystem: MeterSystem
    private lateinit var meterProcessService: MeterProcessService

    private val liveInstruments = Collections.newSetFromMap(ConcurrentHashMap<DeveloperInstrument, Boolean>())
    private val waitingApply = ConcurrentHashMap<String, Handler<AsyncResult<DeveloperInstrument>>>()

    //todo: rethink dev instrument cache, can use storage instead which allows for instrument hits endpoint
    private val developerInstrumentCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build<String, DeveloperInstrument>()

    override suspend fun start() {
        log.info("Starting LiveInstrumentProcessorImpl")
        FeedbackProcessor.module!!.find(StorageModule.NAME).provider().apply {
            metadata = getService(IMetadataQueryDAO::class.java)
        }
        FeedbackProcessor.module!!.find(CoreModule.NAME).provider().apply {
            metricsQueryService = getService(MetricsQueryService::class.java)
            meterSystem = getService(MeterSystem::class.java)
        }
        FeedbackProcessor.module!!.find(AnalyzerModule.NAME).provider().apply {
            meterProcessService = getService(IMeterProcessService::class.java) as MeterProcessService
        }

        vertx.setPeriodic(TimeUnit.SECONDS.toMillis(1)) {
            if (liveInstruments.isNotEmpty()) {
                liveInstruments.forEach {
                    if (it.instrument.pending
                        && it.instrument.expiresAt != null
                        && it.instrument.expiresAt!! <= System.currentTimeMillis()
                    ) {
                        removeLiveInstrument(it.developerAuth, it)
                    }
                }
            }
        }

        //send active instruments on probe connection
        vertx.eventBus().consumer<JsonObject>(REMOTE_REGISTERED) {
            //todo: impl batch instrument add
            //todo: more efficient to just send batch add to specific probe instead of publish to all per connection
            //todo: probably need to redo pending boolean. it doesn't make sense here since pending just means
            // it has been applied to any instrument at all at any point
            val remote = it.body().getString("address").substringBefore(":")
            if (remote == LIVE_INSTRUMENT_REMOTE) {
                log.debug("Live instrument remote registered. Sending active live instruments")
                liveInstruments.forEach {
                    _addLiveInstrument(it.developerAuth, it.instrument, false)
                }
            }
        }

        //listen for instruments applied/removed
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LIVE_INSTRUMENT_APPLIED) {
            handleLiveInstrumentApplied(it)
        }
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LIVE_INSTRUMENT_REMOVED) {
            handleInstrumentRemoved(it)
        }
    }

    override fun addLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        return addLiveInstrument(devAuth, instrument)
    }

    private fun addLiveInstrument(
        devAuth: DeveloperAuth,
        instrument: LiveInstrument
    ): Future<LiveInstrument> {
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
                            put("hit_count", AtomicInteger())
                        }
                    )

                    if (pendingBp.applyImmediately) {
                        addApplyImmediatelyHandler(pendingBp.id!!, promise)
                        _addLiveInstrument(devAuth, pendingBp)
                    } else {
                        _addLiveInstrument(devAuth, pendingBp).onComplete {
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
                            put("hit_count", AtomicInteger())
                        }
                    )

                    if (pendingLog.applyImmediately) {
                        addApplyImmediatelyHandler(pendingLog.id!!, promise)
                        _addLiveInstrument(devAuth, pendingLog)
                    } else {
                        _addLiveInstrument(devAuth, pendingLog).onComplete {
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
                        }
                    )

                    setupLiveMeter(pendingMeter).onComplete {
                        if (it.succeeded()) {
                            if (pendingMeter.applyImmediately) {
                                addApplyImmediatelyHandler(pendingMeter.id!!, promise)
                                _addLiveInstrument(devAuth, pendingMeter)
                            } else {
                                _addLiveInstrument(devAuth, pendingMeter).onComplete {
                                    if (it.succeeded()) {
                                        promise.complete(it.result())
                                    } else {
                                        promise.fail(it.cause())
                                    }
                                }
                            }
                        } else {
                            promise.fail(it.cause())
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
                        }
                    )

                    if (pendingSpan.applyImmediately) {
                        addApplyImmediatelyHandler(pendingSpan.id!!, promise)
                        _addLiveInstrument(devAuth, pendingSpan)
                    } else {
                        _addLiveInstrument(devAuth, pendingSpan).onComplete {
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

        return Future.succeededFuture(_getLiveInstruments(type))
    }

    override fun removeLiveInstrument(id: String): Future<LiveInstrument?> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received remove live instrument request. Developer: {} - Id: {}", devAuth, id)

        return removeLiveInstrument(devAuth, id)
    }

    override fun removeLiveInstruments(location: LiveSourceLocation): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received remove live instruments request. Developer: {} - Location: {}", devAuth, location)

        try {
            val breakpointsResult = removeInstruments(devAuth, location, LiveInstrumentType.BREAKPOINT)
            val logsResult = removeInstruments(devAuth, location, LiveInstrumentType.LOG)
            val metersResult = removeInstruments(devAuth, location, LiveInstrumentType.METER)
            val spansResult = removeInstruments(devAuth, location, LiveInstrumentType.SPAN)

            val promise = Promise.promise<List<LiveInstrument>>()
            CompositeFuture.all(breakpointsResult, logsResult, metersResult, spansResult).onComplete {
                if (it.succeeded()) {
                    promise.complete(it.result().list<List<LiveInstrument>>().flatten())
                } else {
                    promise.fail(it.cause())
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

        return Future.succeededFuture(_getLiveInstrumentById(id))
    }

    override fun getLiveInstrumentsByIds(ids: List<String>): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments by ids request. Developer: {} - Ids: {}", devAuth, ids)

        return Future.succeededFuture(_getLiveInstrumentsByIds(ids))
    }

    override fun getLiveInstrumentsByLocation(location: LiveSourceLocation): Future<List<LiveInstrument>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received get live instruments by location request. Developer: {} - Location: {}", devAuth, location)

        return Future.succeededFuture(_getLiveInstruments(location))
    }

    override fun clearAllLiveInstruments(type: LiveInstrumentType?): Future<Boolean> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received clear live instruments request. Developer: {}", devAuth)

        //todo: impl probe clear command
        val allLiveInstruments = _getLiveInstruments(type)
        allLiveInstruments.forEach {
            removeLiveInstrument(devAuth, it.id!!)
        }
        return Future.succeededFuture(true)
    }

    override fun clearLiveInstruments(type: LiveInstrumentType?): Future<Boolean> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        log.info("Received clear live instruments request. Developer: {}", devAuth.selfId)

        //todo: impl probe clear command
        val devInstruments = liveInstruments.filter {
            it.developerAuth == devAuth && (type == null || it.instrument.type == type)
        }
        devInstruments.forEach {
            removeLiveInstrument(devAuth, it.instrument.id!!)
        }
        return Future.succeededFuture(true)
    }

    private fun handleInstrumentRemoved(it: Message<JsonObject>) {
        if (log.isTraceEnabled) log.trace("Got live instrument removed: {}", it.body())
        val instrumentCommand = it.body().getString("command")
        val instrumentData = if (instrumentCommand != null) {
            val command = ProtocolMarshaller.deserializeLiveInstrumentCommand(JsonObject(instrumentCommand))
            JsonObject.mapFrom(command.instruments.first()) //todo: check for multiple
        } else if (it.body().containsKey("instrument")) {
            JsonObject(it.body().getString("instrument"))
        } else {
            throw IllegalArgumentException("Unknown instrument removed message: $it")
        }

        val instrumentRemoval = liveInstruments.find { find -> find.instrument.id == instrumentData.getString("id") }
        if (instrumentRemoval != null) {
            //publish remove command to all probes & markers
            removeLiveInstrument(
                instrumentRemoval.developerAuth,
                Instant.fromEpochMilliseconds(it.body().getLong("occurredAt")),
                instrumentRemoval.instrument,
                it.body().getString("cause")
            )
        }
    }

    private fun handleLiveInstrumentApplied(it: Message<JsonObject>) {
        val liveInstrument = ProtocolMarshaller.deserializeLiveInstrument(it.body())
        liveInstruments.forEach {
            if (it.instrument.id == liveInstrument.id) {
                log.info("Live instrument applied. Id: {}", it.instrument.id)
                val eventType: LiveInstrumentEventType
                val appliedInstrument: LiveInstrument
                when (liveInstrument.type) {
                    LiveInstrumentType.BREAKPOINT -> {
                        eventType = LiveInstrumentEventType.BREAKPOINT_APPLIED
                        appliedInstrument = (it.instrument as LiveBreakpoint).copy(
                            applied = true,
                            pending = false
                        )
                    }
                    LiveInstrumentType.LOG -> {
                        eventType = LiveInstrumentEventType.LOG_APPLIED
                        appliedInstrument = (it.instrument as LiveLog).copy(
                            applied = true,
                            pending = false
                        )
                    }
                    LiveInstrumentType.METER -> {
                        eventType = LiveInstrumentEventType.METER_APPLIED
                        appliedInstrument = (it.instrument as LiveMeter).copy(
                            applied = true,
                            pending = false
                        )
                    }
                    LiveInstrumentType.SPAN -> {
                        eventType = LiveInstrumentEventType.SPAN_APPLIED
                        appliedInstrument = (it.instrument as LiveSpan).copy(
                            applied = true,
                            pending = false
                        )
                    }
                    else -> throw IllegalArgumentException("Unknown live instrument type")
                }
                (appliedInstrument.meta as MutableMap<String, Any>)["applied_at"] = "${System.currentTimeMillis()}"

                val devInstrument = DeveloperInstrument(it.developerAuth, appliedInstrument)
                liveInstruments.remove(it)
                liveInstruments.add(devInstrument)

                waitingApply.remove(appliedInstrument.id)?.handle(Future.succeededFuture(devInstrument))

                vertx.eventBus().publish(
                    toLiveInstrumentSubscriberAddress(it.developerAuth.selfId),
                    JsonObject.mapFrom(LiveInstrumentEvent(eventType, Json.encode(appliedInstrument)))
                )
                if (log.isTraceEnabled) log.trace("Published live instrument applied")
                return@forEach
            }
        }
    }

    fun addApplyImmediatelyHandler(instrumentId: String, handler: Handler<AsyncResult<LiveInstrument>>) {
        waitingApply[instrumentId] = Handler<AsyncResult<DeveloperInstrument>> {
            if (it.succeeded()) {
                handler.handle(Future.succeededFuture(it.result().instrument))
            } else {
                handler.handle(Future.failedFuture(it.cause()))
            }
        }
    }

    fun _getLiveInstruments(type: LiveInstrumentType? = null): List<LiveInstrument> {
        return liveInstruments.filter { type == null || it.instrument.type == type }.map { it.instrument }.toList()
    }

    fun _getLiveInstruments(location: LiveSourceLocation): List<LiveInstrument> {
        return liveInstruments.filter { it.instrument.location == location }.map { it.instrument }.toList()
    }

    fun _addLiveInstrument(
        devAuth: DeveloperAuth, liveInstrument: LiveInstrument, alertSubscribers: Boolean = true
    ): Future<LiveInstrument> {
        log.debug("Adding live instrument: $liveInstrument")
        val debuggerCommand = LiveInstrumentCommand(CommandType.ADD_LIVE_INSTRUMENT, setOf(liveInstrument))

        val devInstrument = DeveloperInstrument(devAuth, liveInstrument)
        liveInstruments.add(devInstrument)
        dispatchCommand(LIVE_INSTRUMENT_REMOTE, debuggerCommand)

        if (alertSubscribers) {
            val eventType = when (liveInstrument.type) {
                LiveInstrumentType.BREAKPOINT -> LiveInstrumentEventType.BREAKPOINT_ADDED
                LiveInstrumentType.LOG -> LiveInstrumentEventType.LOG_ADDED
                LiveInstrumentType.METER -> LiveInstrumentEventType.METER_ADDED
                LiveInstrumentType.SPAN -> LiveInstrumentEventType.SPAN_ADDED
            }
            vertx.eventBus().publish(
                toLiveInstrumentSubscriberAddress(devAuth.selfId),
                JsonObject.mapFrom(LiveInstrumentEvent(eventType, Json.encode(liveInstrument)))
            )
        }
        return Future.succeededFuture(liveInstrument)
    }

    private fun dispatchCommand(address: String, debuggerCommand: LiveInstrumentCommand) {
        log.trace("Dispatching command ${debuggerCommand.commandType} to connected probe(s)")
        vertx.eventBus().publish(
            address,
            JsonObject.mapFrom(debuggerCommand)
        )
    }

    fun _getDeveloperInstrumentById(id: String): DeveloperInstrument? {
        return liveInstruments.find { it.instrument.id == id }
    }

    fun getCachedDeveloperInstrument(id: String): DeveloperInstrument {
        return developerInstrumentCache.getIfPresent(id)!!
    }

    fun _getLiveInstrumentById(id: String): LiveInstrument? {
        return liveInstruments.find { it.instrument.id == id }?.instrument
    }

    fun _getLiveInstrumentsByIds(ids: List<String>): List<LiveInstrument> {
        return ids.mapNotNull { _getLiveInstrumentById(it) }
    }

    private fun removeLiveInstrument(
        devAuth: DeveloperAuth, occurredAt: Instant, liveInstrument: LiveInstrument, cause: String?
    ) {
        log.debug("Removing live instrument: ${liveInstrument.id}")
        val devInstrument = DeveloperInstrument(devAuth, liveInstrument)
        developerInstrumentCache.put(devInstrument.instrument.id!!, devInstrument)
        liveInstruments.remove(devInstrument)

        val debuggerCommand = LiveInstrumentCommand(CommandType.REMOVE_LIVE_INSTRUMENT, setOf(liveInstrument))
        dispatchCommand(LIVE_INSTRUMENT_REMOTE, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        val waitingHandler = waitingApply.remove(liveInstrument.id)
        if (waitingHandler != null) {
            if (cause?.startsWith("EventBusException") == true) {
                val ebException = ServiceExceptionConverter.fromEventBusException(cause, true)
                waitingHandler.handle(Future.failedFuture(ebException))
            } else {
                TODO("$cause")
            }
        } else {
            val eventType = when (liveInstrument.type) {
                LiveInstrumentType.BREAKPOINT -> LiveInstrumentEventType.BREAKPOINT_REMOVED
                LiveInstrumentType.LOG -> LiveInstrumentEventType.LOG_REMOVED
                LiveInstrumentType.METER -> LiveInstrumentEventType.METER_REMOVED
                LiveInstrumentType.SPAN -> LiveInstrumentEventType.SPAN_REMOVED
            }
            val eventData = Json.encode(LiveInstrumentRemoved(liveInstrument, occurredAt, jvmCause))
            vertx.eventBus().publish(
                toLiveInstrumentSubscriberAddress(devAuth.selfId),
                JsonObject.mapFrom(LiveInstrumentEvent(eventType, eventData))
            )
        }

        if (jvmCause != null) {
            log.warn("Publish live instrument removed. Cause: {} - {}", jvmCause.exceptionType, jvmCause.message)
        } else {
            log.info("Published live instrument removed")
        }
    }

    fun removeLiveInstrument(developerAuth: DeveloperAuth, instrumentId: String): Future<LiveInstrument?> {
        if (log.isTraceEnabled) log.trace("Removing live instrument: $instrumentId")
        val instrumentRemoval = liveInstruments.find { it.instrument.id == instrumentId }
        return if (instrumentRemoval != null) {
            removeLiveInstrument(developerAuth, instrumentRemoval)
        } else {
            Future.succeededFuture()
        }
    }

    fun removeLiveInstrument(
        devAuth: DeveloperAuth, instrumentRemoval: DeveloperInstrument
    ): Future<LiveInstrument?> {
        if (instrumentRemoval.instrument.id == null) {
            //unpublished instrument; just remove from platform
            liveInstruments.remove(instrumentRemoval)
            return Future.succeededFuture(instrumentRemoval.instrument)
        }

        //publish remove command to all probes
        removeLiveInstrument(devAuth, Clock.System.now(), instrumentRemoval.instrument, null)
        return Future.succeededFuture(instrumentRemoval.instrument)
    }

    fun removeInstruments(
        devAuth: DeveloperAuth, location: LiveSourceLocation, instrumentType: LiveInstrumentType
    ): Future<List<LiveInstrument>> {
        log.debug("Removing live instrument(s): $location")
        val debuggerCommand = LiveInstrumentCommand(CommandType.REMOVE_LIVE_INSTRUMENT, locations = setOf(location))

        val result = liveInstruments.filter {
            it.instrument.location == location && it.instrument.type == instrumentType
        }
        liveInstruments.removeAll(result.toSet())
        if (result.isEmpty()) {
            log.info("Could not find live instrument(s) at: $location")
        } else {
            dispatchCommand(LIVE_INSTRUMENT_REMOTE, debuggerCommand)
            log.debug("Removed live instrument(s) at: $location")

            val eventType = when (instrumentType) {
                LiveInstrumentType.BREAKPOINT -> LiveInstrumentEventType.BREAKPOINT_REMOVED
                LiveInstrumentType.LOG -> LiveInstrumentEventType.LOG_REMOVED
                LiveInstrumentType.METER -> LiveInstrumentEventType.METER_REMOVED
                LiveInstrumentType.SPAN -> LiveInstrumentEventType.SPAN_REMOVED
            }
            vertx.eventBus().publish(
                toLiveInstrumentSubscriberAddress(devAuth.selfId),
                JsonObject.mapFrom(LiveInstrumentEvent(eventType, Json.encode(result)))
            )
        }
        return Future.succeededFuture(result.filter { it.instrument.type == instrumentType }.map { it.instrument })
    }

    override fun setupLiveMeter(liveMeter: LiveMeter): Future<JsonObject> {
        val meterConfig = MeterConfig()
        when (liveMeter.meterType) {
            MeterType.COUNT -> {
                meterConfig.metricPrefix = METRIC_PREFIX
                meterConfig.metricsRules = mutableListOf(
                    MeterConfig.Rule().apply {
                        val idVariable = liveMeter.toMetricIdWithoutPrefix()
                        name = idVariable
                        exp = if (Version.CURRENT.buildVersion.startsWith("8")) {
                            "($idVariable.sum(['service', 'instance']).downsampling(SUM)).instance(['service'], ['instance'])"
                        } else if (Version.CURRENT.buildVersion.startsWith("9")) {
                            "($idVariable.sum(['service', 'instance']).downsampling(SUM)).instance(['service'], ['instance'], Layer.GENERAL)"
                        } else {
                            return Future.failedFuture("Unsupported version: ${Version.CURRENT.buildVersion}")
                        }
                    }
                )
            }
            MeterType.GAUGE -> {
                meterConfig.metricPrefix = METRIC_PREFIX
                meterConfig.metricsRules = mutableListOf(
                    MeterConfig.Rule().apply {
                        val idVariable = liveMeter.toMetricIdWithoutPrefix()
                        name = idVariable
                        exp = if (Version.CURRENT.buildVersion.startsWith("8")) {
                            "($idVariable.downsampling(LATEST)).instance(['service'], ['instance'])"
                        } else if (Version.CURRENT.buildVersion.startsWith("9")) {
                            "($idVariable.downsampling(LATEST)).instance(['service'], ['instance'], Layer.GENERAL)"
                        } else {
                            return Future.failedFuture("Unsupported version: ${Version.CURRENT.buildVersion}")
                        }
                    }
                )
            }
            MeterType.HISTOGRAM -> {
                meterConfig.metricPrefix = METRIC_PREFIX
                meterConfig.metricsRules = mutableListOf(
                    MeterConfig.Rule().apply {
                        val idVariable = liveMeter.toMetricIdWithoutPrefix()
                        name = idVariable
                        exp = if (Version.CURRENT.buildVersion.startsWith("8")) {
                            "($idVariable.sum(['le', 'service', 'instance']).increase('PT5M').histogram().histogram_percentile([50,70,90,99])).instance(['service'], ['instance'])"
                        } else if (Version.CURRENT.buildVersion.startsWith("9")) {
                            "($idVariable.sum(['le', 'service', 'instance']).increase('PT5M').histogram().histogram_percentile([50,70,90,99])).instance(['service'], ['instance'], Layer.GENERAL)"
                        } else {
                            return Future.failedFuture("Unsupported version: ${Version.CURRENT.buildVersion}")
                        }
                    }
                )
            }
            else -> throw UnsupportedOperationException("Unsupported meter type: ${liveMeter.meterType}")
        }
        meterProcessService.converts().add(MetricConvert(meterConfig, meterSystem))
        return Future.succeededFuture(JsonObject())
    }

    override fun getLiveMeterMetrics(
        liveMeter: LiveMeter,
        start: Instant,
        stop: Instant,
        step: DurationStep
    ): Future<JsonObject> {
        log.debug("Getting live meter metrics. Metric id: {}", liveMeter.toMetricId())
        val services = metadata.getMeterServices(liveMeter.location.service ?: "")
        if (services.isEmpty()) {
            log.info("No services found")
            return Future.succeededFuture(JsonObject().put("values", JsonArray()))
        }

        val values = mutableListOf<Any>()
        services.forEach { service ->
            val instances = metadata.getMeterServiceInstances(
                start.toEpochMilliseconds(), stop.toEpochMilliseconds(), service.id
            )
            if (instances.isEmpty()) {
                log.info("No instances found for service: ${service.id}")
                return@forEach
            }

            instances.forEach { instance ->
                val serviceInstance = liveMeter.location.serviceInstance
                if (serviceInstance != null && serviceInstance != instance.name) {
                    return@forEach
                }

                val condition = MetricsCondition().apply {
                    name = liveMeter.toMetricId()
                    entity = Entity().apply {
                        setScope(Scope.ServiceInstance)
                        setNormal(true)
                        setServiceName(service.name)
                        setServiceInstanceName(instance.name)
                    }
                }
                if (liveMeter.toMetricId().contains("histogram")) {
                    val value = metricsQueryService.readHeatMap(condition, Duration().apply {
                        Reflect.on(this).set(
                            "start",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(start.toJavaInstant())
                        )
                        Reflect.on(this).set(
                            "end",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(stop.toJavaInstant())
                        )
                        Reflect.on(this).set("step", Step.valueOf(step.name))
                    })
                    values.add(value)
                } else {
                    val value = metricsQueryService.readMetricsValue(condition, Duration().apply {
                        Reflect.on(this).set(
                            "start",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(start.toJavaInstant())
                        )
                        Reflect.on(this).set(
                            "end",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(stop.toJavaInstant())
                        )
                        Reflect.on(this).set("step", Step.valueOf(step.name))
                    })
                    values.add(value)
                }
            }
        }
        return Future.succeededFuture(JsonObject().put("values", JsonArray(values)))
    }
}
