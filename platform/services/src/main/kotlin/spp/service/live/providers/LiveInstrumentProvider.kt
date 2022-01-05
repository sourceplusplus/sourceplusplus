package spp.service.live.providers

import spp.protocol.error.MissingRemoteException
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.LiveInstrumentBatch
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.log.LiveLog
import spp.protocol.instrument.meter.LiveMeter
import spp.protocol.service.live.LiveInstrumentService
import io.vertx.core.*
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.util.RequestContext
import spp.processor.live.LiveInstrumentProcessor
import spp.protocol.instrument.span.LiveSpan
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class LiveInstrumentProvider(
    private val vertx: Vertx,
    private val discovery: ServiceDiscovery
) : LiveInstrumentService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentProvider::class.java)
    }

    private val controller = LiveInstrumentController(vertx)
    private lateinit var liveInstrumentProcessor: LiveInstrumentProcessor

    override fun addLiveInstrument(instrument: LiveInstrument, handler: Handler<AsyncResult<LiveInstrument>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info(
            "Received add live instrument request. Developer: {} - Location: {}",
            selfId, instrument.location.let { it.source + ":" + it.line }
        )

        GlobalScope.launch(vertx.dispatcher()) {
            try {
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
                                put("created_by", selfId)
                                put("hit_count", AtomicInteger())
                            }
                        )

                        if (pendingBp.applyImmediately) {
                            controller.addApplyImmediatelyHandler(pendingBp.id!!, handler)
                            controller.addBreakpoint(selfId, pendingBp)
                        } else {
                            handler.handle(controller.addBreakpoint(selfId, pendingBp))
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
                                put("created_by", selfId)
                                put("hit_count", AtomicInteger())
                            }
                        )

                        if (pendingLog.applyImmediately) {
                            controller.addApplyImmediatelyHandler(pendingLog.id!!, handler)
                            controller.addLog(selfId, pendingLog)
                        } else {
                            handler.handle(controller.addLog(selfId, pendingLog))
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
                                put("created_by", selfId)
                            }
                        )

                        setupLiveMeter(pendingMeter)
                        if (pendingMeter.applyImmediately) {
                            controller.addApplyImmediatelyHandler(pendingMeter.id!!, handler)
                            controller.addMeter(selfId, pendingMeter)
                        } else {
                            handler.handle(controller.addMeter(selfId, pendingMeter))
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
                                put("created_by", selfId)
                            }
                        )

                        if (pendingSpan.applyImmediately) {
                            controller.addApplyImmediatelyHandler(pendingSpan.id!!, handler)
                            controller.addSpan(selfId, pendingSpan)
                        } else {
                            handler.handle(controller.addSpan(selfId, pendingSpan))
                        }
                    }
                    else -> {
                        handler.handle(Future.failedFuture(IllegalArgumentException("Unknown live instrument type")))
                    }
                }
            } catch (throwable: Throwable) {
                log.warn("Add live instrument failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun addLiveInstruments(batch: LiveInstrumentBatch, handler: Handler<AsyncResult<List<LiveInstrument>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info(
            "Received add live instrument batch request. Developer: {} - Location(s): {}",
            selfId, batch.instruments.map { it.location.let { it.source + ":" + it.line } }
        )

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                val results = mutableListOf<LiveInstrument>()
                batch.instruments.forEach {
                    val promise = Promise.promise<LiveInstrument>()
                    RequestContext.put(requestCtx)
                    addLiveInstrument(it, promise)
                    results.add(promise.future().await())
                }
                handler.handle(Future.succeededFuture(results))
            } catch (throwable: Throwable) {
                log.warn("Add live instruments failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun getLiveInstruments(handler: Handler<AsyncResult<List<LiveInstrument>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live instruments request. Developer: {}", selfId)

        handler.handle(Future.succeededFuture(controller.getLiveInstruments()))
    }

    override fun removeLiveInstrument(id: String, handler: Handler<AsyncResult<LiveInstrument?>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received remove live instrument request. Developer: {} - Id: {}", selfId, id)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                handler.handle(controller.removeLiveInstrument(selfId, id))
            } catch (throwable: Throwable) {
                log.warn("Remove live instrument failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun removeLiveInstruments(
        location: LiveSourceLocation, handler: Handler<AsyncResult<List<LiveInstrument>>>
    ) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received remove live instruments request. Developer: {} - Location: {}", selfId, location)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                val breakpointsResult = controller.removeBreakpoints(selfId, location)
                val logsResult = controller.removeLogs(selfId, location)
                val metersResult = controller.removeMeters(selfId, location)

                when {
                    breakpointsResult.failed() -> handler.handle(Future.failedFuture(breakpointsResult.cause()))
                    logsResult.failed() -> handler.handle(Future.failedFuture(logsResult.cause()))
                    metersResult.failed() -> handler.handle(Future.failedFuture(metersResult.cause()))
                    else -> handler.handle(
                        Future.succeededFuture(
                            breakpointsResult.result() + logsResult.result() + metersResult.result()
                        )
                    )
                }
            } catch (throwable: Throwable) {
                log.warn("Remove live instruments failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun getLiveInstrumentById(id: String, handler: Handler<AsyncResult<LiveInstrument?>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live instrument by id request. Developer: {} - Id: {}", selfId, id)

        handler.handle(Future.succeededFuture(controller.getLiveInstrumentById(id)))
    }

    override fun getLiveInstrumentsByIds(ids: List<String>, handler: Handler<AsyncResult<List<LiveInstrument>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live instruments by ids request. Developer: {} - Ids: {}", selfId, ids)

        handler.handle(Future.succeededFuture(controller.getLiveInstrumentsByIds(ids)))
    }

    override fun getLiveBreakpoints(handler: Handler<AsyncResult<List<LiveBreakpoint>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live breakpoints request. Developer: {}", selfId)

        handler.handle(Future.succeededFuture(controller.getActiveLiveBreakpoints()))
    }

    override fun getLiveLogs(handler: Handler<AsyncResult<List<LiveLog>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live logs request. Developer: {}", selfId)

        handler.handle(Future.succeededFuture(controller.getActiveLiveLogs()))
    }

    override fun getLiveMeters(handler: Handler<AsyncResult<List<LiveMeter>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live meters request. Developer: {}", selfId)

        handler.handle(Future.succeededFuture(controller.getActiveLiveMeters()))
    }

    override fun getLiveSpans(handler: Handler<AsyncResult<List<LiveSpan>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received get live spans request. Developer: {}", selfId)

        handler.handle(Future.succeededFuture(controller.getActiveLiveSpans()))
    }

    fun clearAllLiveInstruments() {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"] ?: throw IllegalStateException("Missing self id")
        log.info("Received clear live instruments request. Developer: {}", selfId)

        controller.clearAllLiveInstruments(selfId)
    }

    override fun clearLiveInstruments(handler: Handler<AsyncResult<Boolean>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received clear live instruments request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                handler.handle(controller.clearLiveInstruments(selfId))
            } catch (throwable: Throwable) {
                log.warn("Clear live instruments failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun clearLiveBreakpoints(handler: Handler<AsyncResult<Boolean>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received clear live breakpoints request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                handler.handle(controller.clearLiveBreakpoints(selfId))
            } catch (throwable: Throwable) {
                log.warn("Clear live breakpoints failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun clearLiveLogs(handler: Handler<AsyncResult<Boolean>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received clear live logs request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                handler.handle(controller.clearLiveLogs(selfId))
            } catch (throwable: Throwable) {
                log.warn("Clear live logs failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun clearLiveMeters(handler: Handler<AsyncResult<Boolean>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received clear live meters request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                handler.handle(controller.clearLiveMeters(selfId))
            } catch (throwable: Throwable) {
                log.warn("Clear live meters failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    override fun clearLiveSpans(handler: Handler<AsyncResult<Boolean>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Received clear live spans request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            try {
                handler.handle(controller.clearLiveSpans(selfId))
            } catch (throwable: Throwable) {
                log.warn("Clear live spans failed", throwable)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }

    private suspend fun setupLiveMeter(liveMeter: LiveMeter) {
        log.info("Setting up live meter: $liveMeter")
        initInstrumentProcessor()

        val promise = Promise.promise<LiveInstrumentProcessor>()
        EventBusService.getProxy(discovery, LiveInstrumentProcessor::class.java, promise)
        liveInstrumentProcessor = promise.future().await()

        val async = Promise.promise<JsonObject>()
        liveInstrumentProcessor.setupLiveMeter(liveMeter, async)
        async.future().await()
    }

    private suspend fun initInstrumentProcessor() {
        if (!::liveInstrumentProcessor.isInitialized) {
            try {
                val promise = Promise.promise<LiveInstrumentProcessor>()
                EventBusService.getProxy(discovery, LiveInstrumentProcessor::class.java, promise)
                liveInstrumentProcessor = promise.future().await()
            } catch (ignored: Throwable) {
                log.warn("{} service unavailable", LiveInstrumentProcessor::class.simpleName)
                //todo: this isn't a remote; either create new exception or connect more directly to elasticsearch
                throw MissingRemoteException(LiveInstrumentProcessor::class.java.name).toEventBusException()
            }
        }
    }
}
