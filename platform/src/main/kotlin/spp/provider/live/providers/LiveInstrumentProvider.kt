package spp.provider.live.providers

import com.sourceplusplus.protocol.instrument.LiveInstrument
import com.sourceplusplus.protocol.instrument.LiveInstrumentBatch
import com.sourceplusplus.protocol.instrument.LiveSourceLocation
import com.sourceplusplus.protocol.instrument.breakpoint.LiveBreakpoint
import com.sourceplusplus.protocol.instrument.log.LiveLog
import com.sourceplusplus.protocol.service.live.LiveInstrumentService
import io.vertx.core.*
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.util.RequestContext
import java.util.*

class LiveInstrumentProvider(private val vertx: Vertx) : LiveInstrumentService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentProvider::class.java)
    }

    private val controller = LiveInstrumentController(vertx)

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
                            applied = false
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
                            applied = false
                        )

                        if (pendingLog.applyImmediately) {
                            controller.addApplyImmediatelyHandler(pendingLog.id!!, handler)
                            controller.addLog(selfId, pendingLog)
                        } else {
                            handler.handle(controller.addLog(selfId, pendingLog))
                        }
                    }
                }
            } catch (throwable: Throwable) {
                log.warn("Add live instrument failed. Reason: {}", throwable.message)
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
                log.warn("Add live instruments failed. Reason: {}", throwable.message)
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
                log.warn("Remove live instrument failed. Reason: {}", throwable.message)
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

                when {
                    breakpointsResult.failed() -> handler.handle(Future.failedFuture(breakpointsResult.cause()))
                    logsResult.failed() -> handler.handle(Future.failedFuture(logsResult.cause()))
                    else -> handler.handle(Future.succeededFuture(breakpointsResult.result() + logsResult.result()))
                }
            } catch (throwable: Throwable) {
                log.warn("Remove live instruments failed. Reason: {}", throwable.message)
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
                log.warn("Clear live instruments failed. Reason: {}", throwable.message)
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
                log.warn("Clear live breakpoints failed. Reason: {}", throwable.message)
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
                log.warn("Clear live logs failed. Reason: {}", throwable.message)
                handler.handle(Future.failedFuture(throwable))
            }
        }
    }
}
