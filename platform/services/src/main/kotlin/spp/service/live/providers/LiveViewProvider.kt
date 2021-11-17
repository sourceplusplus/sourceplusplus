package spp.service.live.providers

import spp.protocol.SourceMarkerServices.Provide.LIVE_VIEW_SUBSCRIBER
import spp.protocol.error.MissingRemoteException
import spp.protocol.service.live.LiveViewService
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.LiveViewSubscription
import io.vertx.core.*
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.util.RequestContext
import spp.processor.live.LiveViewProcessor
import spp.protocol.processor.ProcessorAddress
import kotlin.reflect.KClass

class LiveViewProvider(
    private val vertx: Vertx,
    private val discovery: ServiceDiscovery
) : LiveViewService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveViewProvider::class.java)
    }

    private lateinit var liveViewProcessor: LiveViewProcessor

    init {
        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.VIEW_SUBSCRIPTION_EVENT.address) {
            val subscriber = it.body().getString("subscriber")
            val subscription = Json.decodeValue(
                it.body().getJsonObject("subscription").toString(), LiveViewSubscription::class.java
            )
            val event = it.body().getJsonObject("event")
            val viewEvent = if (event.getBoolean("multiMetrics")) {
                val events = event.getJsonArray("metrics")
                val firstEvent = events.getJsonObject(0)
                LiveViewEvent(
                    subscription.entityIds.first(),
                    subscription.artifactQualifiedName,
                    firstEvent.getString("timeBucket"),
                    subscription.liveViewConfig,
                    events.toString()
                )
            } else {
                LiveViewEvent(
                    subscription.entityIds.first(),
                    subscription.artifactQualifiedName,
                    event.getString("timeBucket"),
                    subscription.liveViewConfig,
                    event.toString()
                )
            }
            vertx.eventBus().send("$LIVE_VIEW_SUBSCRIBER.$subscriber", JsonObject.mapFrom(viewEvent))
        }
    }

    override fun addLiveViewSubscription(
        subscription: LiveViewSubscription,
        handler: Handler<AsyncResult<LiveViewSubscription>>
    ) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        val markerId = requestCtx["marker_id"]
        if (markerId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing marker id")))
            return
        }
        log.info("Add live view subscription request. Developer: {} - Subscription: {}", selfId, subscription)

        GlobalScope.launch {
            if (!init(handler)) return@launch
            if (log.isTraceEnabled) log.trace("Adding live view subscription")
            liveViewProcessor.addLiveViewSubscription(markerId, JsonObject.mapFrom(subscription)) {
                if (it.succeeded()) {
                    handler.handle(
                        Future.succeededFuture(
                            Json.decodeValue(it.result().toString(), LiveViewSubscription::class.java)
                        )
                    )
                } else {
                    log.warn("Add live view subscription failed. Reason: {}", it.cause().message)
                    handler.handle(Future.failedFuture(it.cause()))
                }
            }
        }
    }

    override fun removeLiveViewSubscription(
        subscriptionId: String, handler: Handler<AsyncResult<LiveViewSubscription>>
    ) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        val markerId = requestCtx["marker_id"]
        if (markerId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing marker id")))
            return
        }
        log.info("Remove live view subscription request. Developer: {} - Subscription: {}", selfId, subscriptionId)

        GlobalScope.launch {
            if (!init(handler)) return@launch
            if (log.isTraceEnabled) log.trace("Removing live view subscription")
            liveViewProcessor.removeLiveViewSubscription(markerId, subscriptionId) {
                if (it.succeeded()) {
                    handler.handle(
                        Future.succeededFuture(
                            Json.decodeValue(it.result().toString(), LiveViewSubscription::class.java)
                        )
                    )
                } else {
                    log.warn("Remove live view subscription failed. Reason: {}", it.cause().message)
                    handler.handle(Future.failedFuture(it.cause()))
                }
            }
        }
    }

    override fun clearLiveViewSubscriptions(handler: Handler<AsyncResult<List<LiveViewSubscription>>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        val markerId = requestCtx["marker_id"]
        if (markerId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing marker id")))
            return
        }
        log.info("Clear live view subscriptions request. Developer: {}", selfId)

        GlobalScope.launch {
            if (!init(handler)) return@launch
            if (log.isTraceEnabled) log.trace("Clearing live view subscriptions")
            liveViewProcessor.clearLiveViewSubscriptions(markerId) {
                if (it.succeeded()) {
                    val subs = toList(it.result().getString("body").toString(), LiveViewSubscription::class)
                    handler.handle(Future.succeededFuture(subs))
                } else {
                    log.warn("Clear live view subscriptions failed. Reason: {}", it.cause().message)
                    handler.handle(Future.failedFuture(it.cause()))
                }
            }
        }
    }

    fun getLiveViewSubscriptionStats(handler: Handler<AsyncResult<JsonObject>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
        }
        log.info("Get live view subscription stats request. Developer: {}", selfId)

        GlobalScope.launch {
            if (!init(handler)) return@launch
            if (log.isTraceEnabled) log.trace("Getting live view subscription stats")
            liveViewProcessor.getLiveViewSubscriptionStats(handler)
        }
    }

    private suspend fun <T> init(handler: Handler<AsyncResult<T>>): Boolean {
        if (!::liveViewProcessor.isInitialized) {
            try {
                val promise = Promise.promise<LiveViewProcessor>()
                EventBusService.getProxy(discovery, LiveViewProcessor::class.java, promise)
                liveViewProcessor = promise.future().await()
            } catch (ignored: Throwable) {
                log.warn("{} service unavailable", LiveViewProcessor::class.simpleName)
                //todo: this isn't a remote; either create new exception or connect more directly to elasticsearch
                handler.handle(
                    Future.failedFuture(
                        MissingRemoteException(LiveViewProcessor::class.java.name).toEventBusException()
                    )
                )
                return false
            }
        }
        return true
    }

    private fun <T : Any> toList(jsonString: String, clazz: KClass<T>): MutableList<T> {
        val value = Json.decodeValue(jsonString) as JsonArray
        val list = mutableListOf<T>()
        for (it in value.withIndex()) {
            val v = value.getJsonObject(it.index)
            list.add(v.mapTo(clazz.java) as T)
        }
        return list
    }
}
