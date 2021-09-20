package spp.processor.live

import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject

@VertxGen
@ProxyGen
interface LiveViewProcessor {
    fun addLiveViewSubscription(
        subscriberId: String,
        subscription: JsonObject,
        handler: Handler<AsyncResult<JsonObject>>
    )

    fun removeLiveViewSubscription(
        subscriberId: String,
        subscriptionId: String,
        handler: Handler<AsyncResult<JsonObject>>
    )

    fun clearLiveViewSubscriptions(subscriberId: String, handler: Handler<AsyncResult<JsonObject>>)

    fun getLiveViewSubscriptionStats(handler: Handler<AsyncResult<JsonObject>>)
}
