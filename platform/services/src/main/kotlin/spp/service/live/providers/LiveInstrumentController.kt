package spp.service.live.providers

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import spp.platform.probe.ProbeTracker
import spp.protocol.SourceMarkerServices
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.artifact.exception.LiveStackTraceElement
import spp.protocol.artifact.exception.sourceAsLineNumber
import spp.protocol.error.MissingRemoteException
import spp.protocol.instrument.*
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.breakpoint.event.LiveBreakpointHit
import spp.protocol.instrument.breakpoint.event.LiveBreakpointRemoved
import spp.protocol.instrument.log.LiveLog
import spp.protocol.instrument.log.event.LiveLogHit
import spp.protocol.instrument.log.event.LiveLogRemoved
import spp.protocol.instrument.meter.LiveMeter
import spp.protocol.instrument.meter.event.LiveMeterRemoved
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.error.EventBusUtil
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.ProbeAddress.*
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.processor.ProcessorAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveInstrumentController(private val vertx: Vertx) {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentController::class.java)

        private fun toLiveVariable(varName: String, scope: LiveVariableScope?, varData: JsonObject): LiveVariable {
            val liveClass = varData.getString("@class")
            val liveIdentity = varData.getString("@identity")

            val innerVars = mutableListOf<LiveVariable>()
            varData.fieldNames().forEach {
                if (!it.startsWith("@")) {
                    if (varData.get<Any>(it) is JsonObject) {
                        innerVars.add(toLiveVariable(it, null, varData.getJsonObject(it)))
                    } else {
                        innerVars.add(LiveVariable(it, varData[it]))
                    }
                }
            }
            return LiveVariable(varName, innerVars, scope = scope, liveClazz = liveClass, liveIdentity = liveIdentity)
        }

        fun transformRawBreakpointHit(bpData: JsonObject): LiveBreakpointHit {
            val varDatum = bpData.getJsonArray("variables")
            val variables = mutableListOf<LiveVariable>()
            var thisVar: LiveVariable? = null
            for (i in varDatum.list.indices) {
                val varData = varDatum.getJsonObject(i)
                val varName = varData.getJsonObject("data").fieldNames().first()
                val outerVal = JsonObject(varData.getJsonObject("data").getString(varName))
                val scope = LiveVariableScope.valueOf(varData.getString("scope"))

                val liveVar = if (outerVal.get<Any>(varName) is JsonObject) {
                    toLiveVariable(varName, scope, outerVal.getJsonObject(varName))
                } else {
                    LiveVariable(
                        varName, outerVal[varName],
                        scope = scope,
                        liveClazz = outerVal.getString("@class"),
                        liveIdentity = outerVal.getString("@identity")
                    )
                }
                variables.add(liveVar)

                if (liveVar.name == "this") {
                    thisVar = liveVar
                }
            }

            //put instance variables in "this"
            if (thisVar?.value is List<*>) {
                val thisVariables = thisVar.value as MutableList<LiveVariable>?
                variables.filter { it.scope == LiveVariableScope.INSTANCE_FIELD }.forEach { v ->
                    thisVariables?.removeIf { rem ->
                        if (rem.name == v.name) {
                            variables.removeIf { it.name == v.name }
                            true
                        } else {
                            false
                        }
                    }
                    thisVariables?.add(v)
                }
            }

            val stackTrace = LiveStackTrace.fromString(bpData.getString("stack_trace"))!!
            //correct unknown source
            if (stackTrace.first().sourceAsLineNumber() == null) {
                val language = stackTrace.elements[1].source.substringAfter(".").substringBefore(":")
                val actualSource = "${
                    bpData.getString("location_source").substringAfterLast(".")
                }.$language:${bpData.getInteger("location_line")}"
                val correctedElement = LiveStackTraceElement(stackTrace.first().method, actualSource)
                stackTrace.elements.removeAt(0)
                stackTrace.elements.add(0, correctedElement)
            }
            //add live variables
            stackTrace.first().variables.addAll(variables)

            return LiveBreakpointHit(
                bpData.getString("breakpoint_id"),
                bpData.getString("trace_id"),
                Instant.fromEpochMilliseconds(bpData.getLong("occurred_at")),
                bpData.getString("service_instance"),
                bpData.getString("service"),
                stackTrace
            )
        }
    }

    private val liveInstruments = Collections.newSetFromMap(ConcurrentHashMap<DeveloperInstrument, Boolean>())
    private val waitingApply = ConcurrentHashMap<String, Handler<AsyncResult<DeveloperInstrument>>>()

    init {
        vertx.setPeriodic(TimeUnit.SECONDS.toMillis(1)) {
            if (liveInstruments.isNotEmpty()) {
                liveInstruments.forEach {
                    if (it.instrument.pending
                        && it.instrument.expiresAt != null
                        && it.instrument.expiresAt!! <= System.currentTimeMillis()
                    ) {
                        removeLiveInstrument("system", it)
                    }
                }
            }
        }

        //send active instruments on probe connection
        vertx.eventBus().consumer<JsonObject>(ProbeAddress.REMOTE_REGISTERED.address) {
            //todo: impl batch instrument add
            //todo: more efficient to just send batch add to specific probe instead of publish to all per connection
            //todo: probably need to redo pending boolean. it doesn't make sense here since pending just means
            // it has been applied to any instrument at all at any point
            val remote = it.body().getString("address")
            if (remote == LIVE_BREAKPOINT_REMOTE.address) {
                log.debug("Live breakpoint remote registered. Sending active live breakpoints")
                liveInstruments.filter { it.instrument is LiveBreakpoint }.forEach {
                    addBreakpoint(it.selfId, it.instrument as LiveBreakpoint, false)
                }
            }
            if (remote == LIVE_LOG_REMOTE.address) {
                log.debug("Live log remote registered. Sending active live logs")
                liveInstruments.filter { it.instrument is LiveLog }.forEach {
                    addLog(it.selfId, it.instrument as LiveLog, false)
                }
            }
            if (remote == LIVE_METER_REMOTE.address) {
                log.debug("Live meter remote registered. Sending active live meters")
                liveInstruments.filter { it.instrument is LiveMeter }.forEach {
                    addMeter(it.selfId, it.instrument as LiveMeter, false)
                }
            }
        }

        listenForLiveBreakpoints()
        listenForLiveLogs()
        listenForLiveMeters()
    }

    private fun listenForLiveBreakpoints() {
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.LIVE_BREAKPOINT_APPLIED.address) {
            if (log.isTraceEnabled) log.trace("Got live breakpoint applied: {}", it.body())
            val bp = Json.decodeValue(it.body().toString(), LiveBreakpoint::class.java)
            liveInstruments.forEach {
                if (it.instrument.id == bp.id) {
                    log.info("Live breakpoint applied. Id: {}", it.instrument.id)
                    val appliedBp = (it.instrument as LiveBreakpoint).copy(
                        applied = true,
                        pending = false
                    )
                    (appliedBp.meta as MutableMap<String, Any>)["applied_at"] = System.currentTimeMillis().toString()

                    val devInstrument = DeveloperInstrument(it.selfId, appliedBp)
                    liveInstruments.remove(it)
                    liveInstruments.add(devInstrument)

                    waitingApply.remove(appliedBp.id)?.handle(Future.succeededFuture(devInstrument))
                    return@forEach
                }
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.LIVE_BREAKPOINT_REMOVED.address) {
            if (log.isTraceEnabled) log.trace("Got live breakpoint removed: {}", it.body())
            val bpCommand = it.body().getString("command")
            val bpData = if (bpCommand != null) {
                val command = Json.decodeValue(bpCommand, LiveInstrumentCommand::class.java)
                JsonObject(command.context.liveInstruments[0]) //todo: check for multiple
            } else {
                JsonObject(it.body().getString("breakpoint"))
            }

            val instrumentRemoval = liveInstruments.find { find -> find.instrument.id == bpData.getString("id") }
            if (instrumentRemoval != null) {
                //publish remove command to all probes & markers
                removeLiveBreakpoint(
                    instrumentRemoval.selfId,
                    Instant.fromEpochMilliseconds(it.body().getLong("occurredAt")),
                    instrumentRemoval.instrument as LiveBreakpoint,
                    it.body().getString("cause")
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.BREAKPOINT_HIT.address) {
            if (log.isTraceEnabled) log.trace("Live breakpoint hit: {}", it.body())
            val bpHit = transformRawBreakpointHit(it.body())
            val instrument = getLiveInstrumentById(bpHit.breakpointId)
            if (instrument != null) {
                val instrumentMeta = instrument.meta as MutableMap<String, Any>
                if ((instrumentMeta["hit_count"] as AtomicInteger?)?.incrementAndGet() == 1) {
                    instrumentMeta["first_hit_at"] = System.currentTimeMillis().toString()
                }
                instrumentMeta["last_hit_at"] = System.currentTimeMillis().toString()
            }

            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(LiveInstrumentEvent(LiveInstrumentEventType.BREAKPOINT_HIT, Json.encode(bpHit)))
            )
            if (log.isTraceEnabled) log.trace("Published live breakpoint hit")
        }
    }

    private fun listenForLiveLogs() {
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.LOG_HIT.address) {
            if (log.isTraceEnabled) log.trace("Live log hit: {}", it.body())
            val logHit = Json.decodeValue(it.body().toString(), LiveLogHit::class.java)
            val instrument = getLiveInstrumentById(logHit.logId)
            if (instrument != null) {
                val instrumentMeta = instrument.meta as MutableMap<String, Any>
                if ((instrumentMeta["hit_count"] as AtomicInteger?)?.incrementAndGet() == 1) {
                    instrumentMeta["first_hit_at"] = System.currentTimeMillis().toString()
                }
                instrumentMeta["last_hit_at"] = System.currentTimeMillis().toString()
            }

            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(LiveInstrumentEvent(LiveInstrumentEventType.LOG_HIT, it.body().toString()))
            )
            if (log.isTraceEnabled) log.trace("Published live log hit")
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.LIVE_LOG_APPLIED.address) {
            val liveLog = Json.decodeValue(it.body().toString(), LiveLog::class.java)
            liveInstruments.forEach {
                if (it.instrument.id == liveLog.id) {
                    log.info("Live log applied. Id: {}", it.instrument.id)
                    val appliedLog = (it.instrument as LiveLog).copy(
                        applied = true,
                        pending = false
                    )
                    (appliedLog.meta as MutableMap<String, Any>)["applied_at"] = System.currentTimeMillis().toString()

                    val devInstrument = DeveloperInstrument(it.selfId, appliedLog)
                    liveInstruments.remove(it)
                    liveInstruments.add(devInstrument)

                    waitingApply.remove(appliedLog.id)?.handle(Future.succeededFuture(devInstrument))
                    return@forEach
                }
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.LIVE_LOG_REMOVED.address) {
            if (log.isTraceEnabled) log.trace("Got live log removed: {}", it.body())
            val logCommand = it.body().getString("command")
            val logData = if (logCommand != null) {
                val command = Json.decodeValue(logCommand, LiveInstrumentCommand::class.java)
                JsonObject(command.context.liveInstruments[0]) //todo: check for multiple
            } else {
                JsonObject(it.body().getString("log"))
            }

            val instrumentRemoval = liveInstruments.find { find -> find.instrument.id == logData.getString("id") }
            if (instrumentRemoval != null) {
                //publish remove command to all probes & markers
                removeLiveLog(
                    instrumentRemoval.selfId,
                    Instant.fromEpochMilliseconds(it.body().getLong("occurredAt")),
                    instrumentRemoval.instrument as LiveLog,
                    it.body().getString("cause")
                )
            }
        }
    }

    private fun listenForLiveMeters() {
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.LIVE_METER_APPLIED.address) {
            val liveMeter = Json.decodeValue(it.body().toString(), LiveMeter::class.java)
            liveInstruments.forEach {
                if (it.instrument.id == liveMeter.id) {
                    log.info("Live meter applied. Id: {}", it.instrument.id)
                    val appliedMeter = (it.instrument as LiveMeter).copy(
                        applied = true,
                        pending = false
                    )
                    (appliedMeter.meta as MutableMap<String, Any>)["applied_at"] = System.currentTimeMillis().toString()

                    val devInstrument = DeveloperInstrument(it.selfId, appliedMeter)
                    liveInstruments.remove(it)
                    liveInstruments.add(devInstrument)

                    waitingApply.remove(appliedMeter.id)?.handle(Future.succeededFuture(devInstrument))
                    return@forEach
                }
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.LIVE_METER_REMOVED.address) {
            if (log.isTraceEnabled) log.trace("Got live meter removed: {}", it.body())
            val meterCommand = it.body().getString("command")
            val meterData = if (meterCommand != null) {
                val command = Json.decodeValue(meterCommand, LiveInstrumentCommand::class.java)
                JsonObject(command.context.liveInstruments[0]) //todo: check for multiple
            } else {
                JsonObject(it.body().getString("meter"))
            }

            val instrumentRemoval = liveInstruments.find { find -> find.instrument.id == meterData.getString("id") }
            if (instrumentRemoval != null) {
                //publish remove command to all probes & markers
                removeLiveMeter(
                    instrumentRemoval.selfId,
                    Instant.fromEpochMilliseconds(it.body().getLong("occurredAt")),
                    instrumentRemoval.instrument as LiveMeter,
                    it.body().getString("cause")
                )
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

    fun getLiveInstruments(): List<LiveInstrument> {
        return liveInstruments.map { it.instrument }.toList()
    }

    fun getActiveLiveBreakpoints(): List<LiveBreakpoint> {
        return liveInstruments.map { it.instrument }.filterIsInstance(LiveBreakpoint::class.java).filter { !it.pending }
    }

    fun getActiveLiveLogs(): List<LiveLog> {
        return liveInstruments.map { it.instrument }.filterIsInstance(LiveLog::class.java).filter { !it.pending }
    }

    fun getActiveLiveMeters(): List<LiveMeter> {
        return liveInstruments.map { it.instrument }.filterIsInstance(LiveMeter::class.java).filter { !it.pending }
    }

    fun addMeter(
        selfId: String, meter: LiveMeter, alertSubscribers: Boolean = true
    ): AsyncResult<LiveInstrument> {
        log.debug("Adding live meter: $meter")
        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.ADD_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLiveInstrument(meter)

        val devMeter = DeveloperInstrument(selfId, meter)
        liveInstruments.add(devMeter)
        try {
            dispatchCommand(LIVE_METER_REMOTE, meter.location, debuggerCommand)
        } catch (ex: ReplyException) {
            return if (ex.failureType() == ReplyFailure.NO_HANDLERS) {
                if (meter.applyImmediately) {
                    liveInstruments.remove(devMeter)
                    log.warn("Live meter failed due to missing remote(s)")
                    Future.failedFuture(MissingRemoteException(LIVE_METER_REMOTE.address).toEventBusException())
                } else {
                    log.info("Live meter pending application on probe connection")
                    Future.succeededFuture(meter)
                }
            } else {
                liveInstruments.remove(devMeter)
                log.warn("Failed to add live meter: Reason: {}", ex.message)
                Future.failedFuture(ex)
            }
        }

        if (alertSubscribers) {
            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(LiveInstrumentEventType.METER_ADDED, Json.encode(meter))
                )
            )
        }
        return Future.succeededFuture(meter)
    }

    fun addBreakpoint(
        selfId: String, breakpoint: LiveBreakpoint, alertSubscribers: Boolean = true
    ): AsyncResult<LiveInstrument> {
        log.debug("Adding live breakpoint: $breakpoint")
        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.ADD_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLiveInstrument(breakpoint)

        val devBreakpoint = DeveloperInstrument(selfId, breakpoint)
        liveInstruments.add(devBreakpoint)
        try {
            dispatchCommand(LIVE_BREAKPOINT_REMOTE, breakpoint.location, debuggerCommand)
        } catch (ex: ReplyException) {
            return if (ex.failureType() == ReplyFailure.NO_HANDLERS) {
                if (breakpoint.applyImmediately) {
                    liveInstruments.remove(devBreakpoint)
                    log.warn("Live breakpoint failed due to missing remote(s)")
                    Future.failedFuture(MissingRemoteException(LIVE_BREAKPOINT_REMOTE.address).toEventBusException())
                } else {
                    log.info("Live breakpoint pending application on probe connection")
                    Future.succeededFuture(breakpoint)
                }
            } else {
                liveInstruments.remove(devBreakpoint)
                log.warn("Failed to add live breakpoint: Reason: {}", ex.message)
                Future.failedFuture(ex)
            }
        }

        if (alertSubscribers) {
            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(LiveInstrumentEventType.BREAKPOINT_ADDED, Json.encode(breakpoint))
                )
            )
        }
        return Future.succeededFuture(breakpoint)
    }

    private fun dispatchCommand(
        address: ProbeAddress,
        location: LiveSourceLocation,
        debuggerCommand: LiveInstrumentCommand
    ) = GlobalScope.launch(vertx.dispatcher()) {
        log.debug("Dispatching command: {}", debuggerCommand)
        ProbeTracker.getActiveProbes(vertx).filter {
            (location.service == null || it.meta["service"] == location.service) &&
                    (location.serviceInstance == null || it.meta["service_instance"] == location.serviceInstance)
        }.forEach {
            vertx.eventBus().send(address.address + ":" + it.probeId, JsonObject.mapFrom(debuggerCommand))
        }
    }

    fun getLiveInstrumentById(id: String): LiveInstrument? {
        return liveInstruments.find { it.instrument.id == id }?.instrument
    }

    fun getLiveInstrumentsByIds(ids: List<String>): List<LiveInstrument> {
        return ids.mapNotNull { getLiveInstrumentById(it) }
    }

    fun addLog(
        selfId: String, liveLog: LiveLog, alertSubscribers: Boolean = true
    ): AsyncResult<LiveInstrument> {
        log.debug("Adding live log: $liveLog")
        val logCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.ADD_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        logCommand.context.addLiveInstrument(liveLog)

        val devLog = DeveloperInstrument(selfId, liveLog)
        liveInstruments.add(devLog)
        try {
            dispatchCommand(LIVE_LOG_REMOTE, liveLog.location, logCommand)
        } catch (ex: ReplyException) {
            return if (ex.failureType() == ReplyFailure.NO_HANDLERS) {
                if (liveLog.applyImmediately) {
                    liveInstruments.remove(devLog)
                    log.warn("Live log failed due to missing remote")
                    Future.failedFuture(MissingRemoteException(LIVE_LOG_REMOTE.address).toEventBusException())
                } else {
                    log.info("Live log pending application on probe connection")
                    Future.succeededFuture(liveLog)
                }
            } else {
                liveInstruments.remove(devLog)
                log.warn("Failed to add live log: Reason: {}", ex.message)
                Future.failedFuture(ex)
            }
        }

        if (alertSubscribers) {
            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(LiveInstrumentEventType.LOG_ADDED, Json.encode(liveLog))
                )
            )
        }
        return Future.succeededFuture(liveLog)
    }

    private fun removeLiveBreakpoint(selfId: String, occurredAt: Instant, breakpoint: LiveBreakpoint, cause: String?) {
        log.debug("Removing live breakpoint: ${breakpoint.id}")
        val devBreakpoint = DeveloperInstrument(selfId, breakpoint)
        liveInstruments.remove(devBreakpoint)

        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.REMOVE_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLiveInstrument(breakpoint)
        dispatchCommand(LIVE_BREAKPOINT_REMOTE, breakpoint.location, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        val waitingHandler = waitingApply.remove(breakpoint.id)
        if (waitingHandler != null) {
            if (cause?.startsWith("EventBusException") == true) {
                val ebException = EventBusUtil.fromEventBusException(cause)
                waitingHandler.handle(Future.failedFuture(ebException))
            } else {
                TODO("$cause")
            }
        } else {
            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(
                        LiveInstrumentEventType.BREAKPOINT_REMOVED,
                        //todo: could send whole breakpoint instead of just id
                        Json.encode(LiveBreakpointRemoved(breakpoint.id!!, occurredAt, jvmCause))
                    )
                )
            )
        }

        if (jvmCause != null) {
            log.warn("Publish live breakpoint removed. Cause: {}", jvmCause.message)
        } else {
            log.info("Published live breakpoint removed")
        }
    }

    private fun removeLiveLog(selfId: String, occurredAt: Instant, liveLog: LiveLog, cause: String?) {
        log.debug("Removing live log: ${liveLog.id}")
        val devLog = DeveloperInstrument(selfId, liveLog)
        liveInstruments.remove(devLog)

        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.REMOVE_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLiveInstrument(liveLog)
        dispatchCommand(LIVE_LOG_REMOTE, liveLog.location, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        val waitingHandler = waitingApply.remove(liveLog.id)
        if (waitingHandler != null) {
            if (cause?.startsWith("EventBusException") == true) {
                val ebException = EventBusUtil.fromEventBusException(cause)
                waitingHandler.handle(Future.failedFuture(ebException))
            } else {
                TODO("$cause")
            }
        } else {
            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(
                        LiveInstrumentEventType.LOG_REMOVED,
                        //todo: could send whole log instead of just id
                        Json.encode(LiveLogRemoved(liveLog.id!!, occurredAt, jvmCause, liveLog))
                    )
                )
            )
        }

        if (jvmCause != null) {
            log.warn("Publish live log removed. Cause: {} - {}", jvmCause.exceptionType, jvmCause.message)
        } else {
            log.info("Published live log removed")
        }
    }

    private fun removeLiveMeter(selfId: String, occurredAt: Instant, meter: LiveMeter, cause: String?) {
        log.debug("Removing live meter: ${meter.id}")
        val devMeter = DeveloperInstrument(selfId, meter)
        liveInstruments.remove(devMeter)

        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.REMOVE_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLiveInstrument(meter)
        dispatchCommand(LIVE_METER_REMOTE, meter.location, debuggerCommand)

        val jvmCause = if (cause == null) null else LiveStackTrace.fromString(cause)
        val waitingHandler = waitingApply.remove(meter.id)
        if (waitingHandler != null) {
            if (cause?.startsWith("EventBusException") == true) {
                val ebException = EventBusUtil.fromEventBusException(cause)
                waitingHandler.handle(Future.failedFuture(ebException))
            } else {
                TODO("$cause")
            }
        } else {
            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(
                        LiveInstrumentEventType.METER_REMOVED,
                        //todo: could send whole meter instead of just id
                        Json.encode(LiveMeterRemoved(meter.id!!, occurredAt, jvmCause))
                    )
                )
            )
        }

        if (jvmCause != null) {
            log.warn("Publish live meter removed. Cause: {}", jvmCause.message)
        } else {
            log.info("Published live meter removed")
        }
    }

    fun removeLiveInstrument(selfId: String, instrumentId: String): AsyncResult<LiveInstrument?> {
        if (log.isTraceEnabled) log.trace("Removing live instrument: $instrumentId")
        val instrumentRemoval = liveInstruments.find { it.instrument.id == instrumentId }
        return if (instrumentRemoval != null) {
            removeLiveInstrument(selfId, instrumentRemoval)
        } else {
            Future.succeededFuture()
        }
    }

    fun removeLiveInstrument(selfId: String, instrumentRemoval: DeveloperInstrument): AsyncResult<LiveInstrument?> {
        if (instrumentRemoval.instrument.id == null) {
            //unpublished instrument; just remove from platform
            liveInstruments.remove(instrumentRemoval)
            return Future.succeededFuture(instrumentRemoval.instrument)
        }

        //publish remove command to all probes
        when (instrumentRemoval.instrument) {
            is LiveBreakpoint -> removeLiveBreakpoint(selfId, Clock.System.now(), instrumentRemoval.instrument, null)
            is LiveLog -> removeLiveLog(selfId, Clock.System.now(), instrumentRemoval.instrument, null)
            is LiveMeter -> removeLiveMeter(selfId, Clock.System.now(), instrumentRemoval.instrument, null)
            else -> TODO()
        }

        return Future.succeededFuture(instrumentRemoval.instrument)
    }

    fun removeBreakpoints(selfId: String, location: LiveSourceLocation): AsyncResult<List<LiveInstrument>> {
        log.debug("Removing live breakpoint(s): $location")
        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.REMOVE_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLocation(location)

        val result = liveInstruments.filter { it.instrument.location == location && it.instrument is LiveBreakpoint }
        liveInstruments.removeAll(result.toSet())
        if (result.isEmpty()) {
            log.info("Could not find live breakpoint(s) at: $location")
        } else {
            dispatchCommand(LIVE_BREAKPOINT_REMOTE, location, debuggerCommand)
            log.debug("Removed live breakpoint(s) at: $location")

            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(LiveInstrumentEventType.BREAKPOINT_REMOVED, Json.encode(result))
                )
            )
        }
        return Future.succeededFuture(result.map { it.instrument as LiveBreakpoint })
    }

    fun removeLogs(selfId: String, location: LiveSourceLocation): AsyncResult<List<LiveInstrument>> {
        log.debug("Removing live log(s): $location")
        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.REMOVE_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLocation(location)

        val result = liveInstruments.filter { it.instrument.location == location && it.instrument is LiveLog }
        liveInstruments.removeAll(result.toSet())
        if (result.isEmpty()) {
            log.info("Could not find live log(s) at: $location")
        } else {
            dispatchCommand(LIVE_LOG_REMOTE, location, debuggerCommand)
            log.debug("Removed live log(s) at: $location")

            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(LiveInstrumentEventType.LOG_REMOVED, Json.encode(result))
                )
            )
        }
        return Future.succeededFuture(result.map { it.instrument as LiveLog })
    }

    fun removeMeters(selfId: String, location: LiveSourceLocation): AsyncResult<List<LiveInstrument>> {
        log.debug("Removing live meter(s): $location")
        val debuggerCommand = LiveInstrumentCommand(
            LiveInstrumentCommand.CommandType.REMOVE_LIVE_INSTRUMENT,
            LiveInstrumentContext()
        )
        debuggerCommand.context.addLocation(location)

        val result = liveInstruments.filter { it.instrument.location == location && it.instrument is LiveMeter }
        liveInstruments.removeAll(result.toSet())
        if (result.isEmpty()) {
            log.info("Could not find live meter(s) at: $location")
        } else {
            dispatchCommand(LIVE_METER_REMOTE, location, debuggerCommand)
            log.debug("Removed live meter(s) at: $location")

            vertx.eventBus().publish(
                SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER,
                JsonObject.mapFrom(
                    LiveInstrumentEvent(LiveInstrumentEventType.METER_REMOVED, Json.encode(result))
                )
            )
        }
        return Future.succeededFuture(result.map { it.instrument as LiveMeter })
    }

    //todo: impl probe clear command
    fun clearAllLiveInstruments(selfId: String): AsyncResult<Boolean> {
        val allLiveInstruments = getLiveInstruments()
        allLiveInstruments.forEach {
            removeLiveInstrument(selfId, it.id!!)
        }
        return Future.succeededFuture(true)
    }

    //todo: impl probe clear command
    fun clearLiveInstruments(selfId: String): AsyncResult<Boolean> {
        val devInstruments = liveInstruments.filter { it.selfId == selfId }
        devInstruments.forEach {
            removeLiveInstrument(selfId, it.instrument.id!!)
        }
        return Future.succeededFuture(true)
    }

    //todo: impl probe clear command
    fun clearLiveBreakpoints(selfId: String): AsyncResult<Boolean> {
        val devBreakpoints = liveInstruments.filter { it.selfId == selfId && it.instrument is LiveBreakpoint }
        devBreakpoints.forEach {
            removeLiveInstrument(selfId, it.instrument.id!!)
        }
        return Future.succeededFuture(true)
    }

    fun clearLiveLogs(selfId: String): AsyncResult<Boolean> {
        val devLogs = liveInstruments.filter { it.selfId == selfId && it.instrument is LiveLog }
        devLogs.forEach {
            removeLiveInstrument(selfId, it.instrument.id!!)
        }
        return Future.succeededFuture(true)
    }

    fun clearLiveMeters(selfId: String): AsyncResult<Boolean> {
        val devMeters = liveInstruments.filter { it.selfId == selfId && it.instrument is LiveMeter }
        devMeters.forEach {
            removeLiveInstrument(selfId, it.instrument.id!!)
        }
        return Future.succeededFuture(true)
    }

    data class DeveloperInstrument(
        val selfId: String,
        val instrument: LiveInstrument
    ) {
        //todo: verify selfId isn't needed in equals/hashcode
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeveloperInstrument) return false
            if (instrument != other.instrument) return false
            return true
        }

        override fun hashCode(): Int = Objects.hash(selfId, instrument)
    }
}
