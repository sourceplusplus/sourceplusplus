/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.platform.probe

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.core.util.Msg.msg
import spp.protocol.platform.PlatformAddress
import spp.protocol.status.ActiveProbe
import spp.protocol.probe.ProbeAddress
import spp.protocol.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProbeTracker : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val activeProbes: MutableMap<String, ActiveProbe> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(activeProbesAddress) {
            launch(vertx.dispatcher()) {
                it.reply(ArrayList(activeProbes.values))
            }
        }
        vertx.eventBus().consumer<JsonObject>(connectedProbesAddress) {
            launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        PlatformAddress.PROBE_CONNECTED.address
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(ProbeAddress.REMOTE_REGISTERED.address) {
            val remote = it.body().getString("address").substringBefore(":")
            val probeId = it.headers().get("probe_id")
            activeProbes[probeId]!!.remotes.add(remote)
            log.trace { msg("Probe {} registered {}", probeId, remote) }

            launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(remote).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_CONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { msg("Establishing connection with probe {}", conn.instanceId) }

            activeProbes[conn.instanceId] = ActiveProbe(conn.instanceId, System.currentTimeMillis(), meta = conn.meta)
            it.reply(true)

            log.info(
                "Probe connected. Latency: {}ms - Probes connected: {}",
                latency, activeProbes.size
            )

            launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROBE_CONNECTED.address).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_DISCONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val activeProbe = activeProbes.remove(conn.instanceId)!!
            val connectedAt = Instant.ofEpochMilli(activeProbe.connectedAt)
            log.info("Probe disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

            launch(vertx.dispatcher()) {
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
