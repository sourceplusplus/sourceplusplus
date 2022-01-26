package spp.platform.marker

import spp.protocol.SourceMarkerServices
import spp.protocol.status.MarkerConnection
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.client.ActiveMarker
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class MarkerTracker(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val activeMarkers: MutableMap<String, ActiveMarker> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(activeMarkersAddress) {
            GlobalScope.launch(vertx.dispatcher()) {
                it.reply(ArrayList(activeMarkers.values))
            }
        }
        vertx.eventBus().consumer<JsonObject>(connectedMarkersAddress) {
            GlobalScope.launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        PlatformAddress.MARKER_CONNECTED.address
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.MARKER_CONNECTED.address) { marker ->
            val conn = Json.decodeValue(marker.body().toString(), MarkerConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { "Establishing connection with marker ${conn.markerId}" }

            if (jwtAuth != null && !marker.headers().get("token").isNullOrEmpty()) {
                jwtAuth.authenticate(JsonObject().put("token", marker.headers().get("token"))).onComplete {
                    if (it.succeeded()) {
                        addActiveMarker(it.result().principal().getString("developer_id"), conn, marker, latency)
                    } else {
                        log.warn("Rejected invalid marker access")
                        marker.reply(false)
                    }
                }
            } else {
                addActiveMarker("system", conn, marker, latency)
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.MARKER_DISCONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), MarkerConnection::class.java)
            val activeMarker = activeMarkers.remove(conn.markerId)
            if (activeMarker != null) {
                val connectedAt = Instant.ofEpochMilli(activeMarker.connectedAt)
                log.info("Marker disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

                GlobalScope.launch(vertx.dispatcher()) {
                    vertx.sharedData().getLocalCounter(PlatformAddress.MARKER_CONNECTED.address).await()
                        .decrementAndGet().await()
                }
            }
        }
    }

    private fun addActiveMarker(selfId: String, conn: MarkerConnection, marker: Message<JsonObject>, latency: Long) {
        activeMarkers[conn.markerId] = ActiveMarker(conn.markerId, System.currentTimeMillis(), selfId, meta = conn.meta)
        marker.reply(true)

        log.info(
            "Marker connected. Latency: {}ms - Active markers: {}",
            latency, activeMarkers.size
        )

        GlobalScope.launch(vertx.dispatcher()) {
            vertx.sharedData().getLocalCounter(PlatformAddress.MARKER_CONNECTED.address).await()
                .incrementAndGet().await()
        }
    }

    companion object {
        private const val connectedMarkersAddress = "get-connected-markers"
        private const val activeMarkersAddress = "get-active-markers"

        suspend fun getConnectedMarkerCount(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedMarkersAddress, null).await().body()
        }

        suspend fun getActiveMarkers(vertx: Vertx): List<ActiveMarker> {
            return vertx.eventBus().request<List<ActiveMarker>>(activeMarkersAddress, null).await().body()
        }
    }
}
