package spp.processor.logging

import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import io.vertx.core.AsyncResult
import io.vertx.core.Handler

@VertxGen
@ProxyGen
interface LoggingProcessor {
    fun getPatternOccurredCounts(handler: Handler<AsyncResult<Map<String, Int>>>)
}
