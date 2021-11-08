package spp.service.logging.providers

import spp.protocol.artifact.log.LogCountSummary
import spp.protocol.error.MissingRemoteException
import spp.protocol.service.logging.LogCountIndicatorService
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.slf4j.LoggerFactory
import spp.processor.logging.LoggingProcessor
import spp.platform.util.RequestContext

class LogCountIndicator(private val discovery: ServiceDiscovery) : LogCountIndicatorService {

    companion object {
        private val log = LoggerFactory.getLogger(LogCountIndicator::class.java)
    }

    lateinit var loggingProcessor: LoggingProcessor

    override fun getLogCountSummary(handler: Handler<AsyncResult<LogCountSummary>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Get log count summary request. Developer: {}", selfId)

        GlobalScope.launch {
            if (!::loggingProcessor.isInitialized) {
                try {
                    val promise = Promise.promise<LoggingProcessor>()
                    EventBusService.getProxy(discovery, LoggingProcessor::class.java, promise)
                    loggingProcessor = promise.future().await()
                } catch (ignored: Throwable) {
                    log.warn("{} service unavailable", LoggingProcessor::class.simpleName)
                    //todo: this isn't a remote; either create new exception or connect more directly to elasticsearch
                    handler.handle(
                        Future.failedFuture(
                            MissingRemoteException(LoggingProcessor::class.java.name).toEventBusException()
                        )
                    )
                    return@launch
                }
            }

            if (log.isTraceEnabled) log.trace("Getting log pattern occurrences")
            loggingProcessor.getPatternOccurredCounts {
                if (it.succeeded()) {
                    if (log.isTraceEnabled) log.trace("Sent log pattern occurrences")
                    handler.handle(Future.succeededFuture(LogCountSummary(Clock.System.now(), it.result())))
                } else {
                    log.warn("Get log count summary failed. Reason: {}", it.cause().message)
                    handler.handle(Future.failedFuture(it.cause()))
                }
            }
        }
    }
}
