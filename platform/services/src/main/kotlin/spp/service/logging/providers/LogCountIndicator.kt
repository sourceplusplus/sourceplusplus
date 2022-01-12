package spp.service.logging.providers

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import spp.platform.util.RequestContext
import spp.processor.logging.LoggingProcessor
import spp.protocol.artifact.log.LogCountSummary
import spp.protocol.error.MissingRemoteException
import spp.protocol.instrument.DurationStep
import spp.protocol.service.logging.LogCountIndicatorService

class LogCountIndicator(private val discovery: ServiceDiscovery) : LogCountIndicatorService {

    companion object {
        private val log = LoggerFactory.getLogger(LogCountIndicator::class.java)
    }

    lateinit var loggingProcessor: LoggingProcessor

    override fun getPatternOccurrences(
        logPatterns: List<String>,
        serviceName: String?,
        start: Instant,
        stop: Instant,
        step: DurationStep,
        handler: Handler<AsyncResult<JsonObject>>
    ) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Getting log count occurrence patterns. Patterns: {}", logPatterns)

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
            loggingProcessor.getPatternOccurrences(logPatterns, serviceName, start, stop, step) {
                if (it.succeeded()) {
                    handler.handle(Future.succeededFuture(it.result()))
                } else {
                    handler.handle(Future.failedFuture(it.cause()))
                }
            }
        }
    }

    override fun getLogCountSummary(handler: Handler<AsyncResult<LogCountSummary>>) {
        TODO()
    }
}
