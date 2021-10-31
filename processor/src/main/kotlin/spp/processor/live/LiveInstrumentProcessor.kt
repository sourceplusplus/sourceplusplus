package spp.processor.live

import com.sourceplusplus.protocol.instrument.meter.LiveMeter
import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject

@VertxGen
@ProxyGen
interface LiveInstrumentProcessor {
    fun setupLiveMeter(
        liveMeter: LiveMeter,
        handler: Handler<AsyncResult<JsonObject>>
    )
}
