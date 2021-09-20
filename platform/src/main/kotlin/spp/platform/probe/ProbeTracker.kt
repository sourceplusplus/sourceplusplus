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
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.status.ProbeConnection
import java.util.concurrent.ConcurrentHashMap

class ProbeTracker : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val activeProbes: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    override suspend fun start() {
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
            activeProbes[probeId]!!.add(remote)
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

            activeProbes[conn.probeId] = ConcurrentHashMap.newKeySet()
            it.reply(true)

            log.info(
                "Probe connection established. Latency: {}ms - Probes connected: {}",
                latency, activeProbes.size
            )

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROBE_CONNECTED.address).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_DISCONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), ProbeConnection::class.java)
            val registeredRemotes = activeProbes.remove(conn.probeId)!!
            log.info("Probe disconnected") //todo: could report total connection time

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROBE_CONNECTED.address).await()
                    .decrementAndGet().await()

                registeredRemotes.forEach {
                    vertx.sharedData().getLocalCounter(it).await()
                        .decrementAndGet().await()
                }
            }
        }
    }

    companion object {
        private const val connectedProbesAddress = "get-connected-probes"

        suspend fun getConnectedProbes(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedProbesAddress, null).await().body()
        }
    }
}
