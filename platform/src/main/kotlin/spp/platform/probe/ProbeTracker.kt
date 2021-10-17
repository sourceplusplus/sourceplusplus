package spp.platform.probe

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.util.Msg.msg
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.client.ActiveProbe
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.status.ProbeConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProbeTracker : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val activeProbes: MutableMap<String, ActiveProbe> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(activeProbesAddress) {
            GlobalScope.launch(vertx.dispatcher()) {
                it.reply(ArrayList(activeProbes.values))
            }
        }
        vertx.eventBus().consumer<JsonObject>(connectedProbesAddress) {
            GlobalScope.launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        PlatformAddress.PROBE_CONNECTED.address
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(ProbeAddress.REMOTE_REGISTERED.address) {
            val remote = it.body().getString("address")
            val probeId = it.headers().get("probe_id")
            activeProbes[probeId]!!.remotes.add(remote)
            log.trace { msg("Probe {} registered {}", probeId, remote) }

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(remote).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_CONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), ProbeConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { msg("Establishing connection with probe {}", conn.probeId) }

            activeProbes[conn.probeId] = ActiveProbe(conn.probeId, System.currentTimeMillis())
            it.reply(true)

            log.info(
                "Probe connected. Latency: {}ms - Probes connected: {}",
                latency, activeProbes.size
            )

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROBE_CONNECTED.address).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_DISCONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), ProbeConnection::class.java)
            val activeProbe = activeProbes.remove(conn.probeId)!!
            val connectedAt = Instant.ofEpochMilli(activeProbe.connectedAt)
            log.info("Probe disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROBE_CONNECTED.address).await()
                    .decrementAndGet().await()

                activeProbe.remotes.forEach {
                    vertx.sharedData().getLocalCounter(it).await()
                        .decrementAndGet().await()
                }
            }
        }
    }

    companion object {
        private const val connectedProbesAddress = "get-connected-probes"
        private const val activeProbesAddress = "get-active-probes"

        suspend fun getConnectedProbeCount(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedProbesAddress, null).await().body()
        }

        suspend fun getActiveProbes(vertx: Vertx): List<ActiveProbe> {
            return vertx.eventBus().request<List<ActiveProbe>>(activeProbesAddress, null).await().body()
        }
    }
}
