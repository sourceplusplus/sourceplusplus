package spp.processor.live.impl

import com.sourceplusplus.protocol.view.LiveViewSubscription
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import spp.processor.InstrumentProcessor
import spp.processor.live.LiveViewProcessor
import spp.processor.live.impl.view.LiveActivityView
import spp.processor.live.impl.view.LiveLogsView
import spp.processor.live.impl.view.LiveTracesView
import spp.processor.live.impl.view.util.EntitySubscribersCache
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.processor.live.impl.view.util.ViewSubscriber
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.processor.ProcessorAddress.VIEW_SUBSCRIPTION_EVENT
import java.util.*

class LiveViewProcessorImpl : CoroutineVerticle(), LiveViewProcessor {

    companion object {
        private val log = LoggerFactory.getLogger(LiveViewProcessorImpl::class.java)
    }

    private var eventPublishRateLimit = 1000 //todo: set as global min limit
    private val subscriptionCache = MetricTypeSubscriptionCache()
    val activityView = LiveActivityView(subscriptionCache)
    val tracesView = LiveTracesView(subscriptionCache)
    val logsView = LiveLogsView(subscriptionCache)

    override suspend fun start() {
        log.info("Starting LiveViewProcessorImpl")
        //manage live view subscriptions on marker disconnection
        FrameHelper.sendFrame(
            BridgeEventType.REGISTER.name.toLowerCase(),
            MARKER_DISCONNECTED.address,
            JsonObject(),
            InstrumentProcessor.tcpSocket
        )

        vertx.eventBus().consumer<JsonObject>("local." + MARKER_DISCONNECTED.address) {
            val markerId = it.body().getString("markerId")
            clearLiveViewSubscriptions(markerId) {
                if (it.succeeded()) {
                    log.info("Cleared live view subscriptions for disconnected marker: {}", markerId)
                } else {
                    log.error("Failed to clear live view subscriptions on marker disconnection", it.cause())
                }
            }
        }
    }

    override fun addLiveViewSubscription(
        subscriberId: String,
        subscription: JsonObject,
        handler: Handler<AsyncResult<JsonObject>>
    ) {
        val address = "view." + UUID.randomUUID().toString()
        val sub = Json.decodeValue(subscription.toString(), LiveViewSubscription::class.java)
            .copy(subscriptionId = address)

        val consumer = vertx.eventBus().consumer<JsonObject>(address)
        consumer.handler {
            vertx.eventBus().send(
                VIEW_SUBSCRIPTION_EVENT.address, JsonObject()
                    .put("address", address)
                    .put("subscriber", subscriberId)
                    .put("subscription", subscription)
                    .put("event", it.body())
            )
        }.completionHandler {
            if (it.succeeded()) {
                val subscriber = ViewSubscriber(
                    sub,
                    subscriberId,
                    System.currentTimeMillis(),
                    mutableListOf(),
                    consumer
                )
                sub.liveViewConfig.viewMetrics.forEach {
                    subscriptionCache.computeIfAbsent(it) { EntitySubscribersCache() }
                    sub.entityIds.forEach { entityId ->
                        subscriptionCache[it]!!.computeIfAbsent(entityId) { mutableSetOf() }
                        (subscriptionCache[it]!![entityId]!! as MutableSet).add(subscriber)
                    }
                }

                handler.handle(Future.succeededFuture(JsonObject.mapFrom(sub)))
            } else {
                handler.handle(Future.failedFuture(it.cause()))
            }
        }
    }

    override fun removeLiveViewSubscription(
        subscriberId: String, subscriptionId: String, handler: Handler<AsyncResult<JsonObject>>
    ) {
        var unsubbedUser: ViewSubscriber? = null
        subscriptionCache.flatMap { it.value.values }.forEach { subList ->
            val subscription = subList.firstOrNull { it.subscription.subscriptionId == subscriptionId }
            if (subscription != null) {
                (subList as MutableSet).remove(subscription)
                unsubbedUser = subscription
            }
        }

        if (unsubbedUser != null) {
            unsubbedUser!!.consumer.unregister {
                if (it.succeeded()) {
                    handler.handle(
                        Future.succeededFuture(
                            JsonObject.mapFrom(
                                LiveViewSubscription(
                                    unsubbedUser!!.subscription.subscriptionId,
                                    unsubbedUser!!.subscription.entityIds,
                                    unsubbedUser!!.subscription.artifactQualifiedName,
                                    unsubbedUser!!.subscription.liveViewConfig
                                )
                            )
                        )
                    )
                } else {
                    handler.handle(Future.failedFuture(it.cause()))
                }
            }
        } else {
            handler.handle(Future.failedFuture(IllegalStateException("Invalid subscription id")))
        }
    }

    override fun clearLiveViewSubscriptions(subscriberId: String, handler: Handler<AsyncResult<JsonObject>>) {
        val removedSubs = mutableSetOf<ViewSubscriber>()
        subscriptionCache.entries.removeIf {
            it.value.entries.removeIf {
                (it.value as MutableSet).removeIf {
                    if (it.subscriberId == subscriberId) {
                        removedSubs.add(it)
                        true
                    } else false
                }
                it.value.isEmpty()
            }
            it.value.isEmpty()
        }
        if (removedSubs.isNotEmpty()) {
            removedSubs.forEach {
                it.consumer.unregister()
            }
            handler.handle(
                Future.succeededFuture(
                    JsonObject()
                        .put("body", JsonArray(Json.encode(removedSubs.map { it.subscription })))
                )
            )
        } else {
            handler.handle(Future.succeededFuture(JsonObject().put("body", JsonArray())))
        }
    }

    override fun getLiveViewSubscriptionStats(handler: Handler<AsyncResult<JsonObject>>) {
        val subStats = JsonObject()
        subscriptionCache.forEach { type ->
            subStats.put(type.key, JsonObject())
            type.value.forEach { key, value ->
                subStats.getJsonObject(type.key).put(key, value.size)
            }
        }
        handler.handle(Future.succeededFuture(subStats))
    }

    override suspend fun stop() {
        log.info("Stopping LiveViewProcessorImpl")
    }
}
