package spp.platform.marker

import com.sourceplusplus.protocol.SourceMarkerServices
import com.sourceplusplus.protocol.status.MarkerConnection
import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.protocol.platform.PlatformAddress
import java.util.concurrent.ConcurrentHashMap

class MarkerTracker : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val activeMarkers: MutableMap<String, String> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(connectedMarkersAddress) {
            GlobalScope.launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        SourceMarkerServices.Status.MARKER_CONNECTED
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(SourceMarkerServices.Status.MARKER_CONNECTED) {
            val conn = Json.decodeValue(it.body().toString(), MarkerConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { "Establishing connection with marker ${conn.markerId}" }

            activeMarkers[conn.markerId] = "hello" //can save time, etc; ConnectionDataDTO
            it.reply(true)

            log.info(
                "Marker connection established. Latency: {}ms - Active markers: {}",
                latency, activeMarkers.size
            )

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(SourceMarkerServices.Status.MARKER_CONNECTED).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.MARKER_DISCONNECTED.address) {
            activeMarkers.remove(it.body().getString("markerId"))
            log.info("Marker disconnected")

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(SourceMarkerServices.Status.MARKER_CONNECTED).await()
                    .decrementAndGet().await()
            }
        }
    }

    companion object {
        private const val connectedMarkersAddress = "get-connected-markers"

        suspend fun getConnectedMarkers(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedMarkersAddress, null).await().body()
        }
    }
}
