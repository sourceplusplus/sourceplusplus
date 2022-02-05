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
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.core.InstanceBridge
import spp.platform.core.SourceSubscriber
import spp.platform.core.util.Msg
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.status.ActiveInstance
import spp.protocol.platform.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProbeBridge(
    private val router: Router,
    jwtAuth: JWTAuth?,
    private val netServerOptions: NetServerOptions
) : InstanceBridge(jwtAuth) {

    companion object {
        private val log = KotlinLogging.logger {}

        private const val connectedProbesAddress = "get-connected-probes"
        private const val activeProbesAddress = "get-active-probes"

        suspend fun getConnectedProbeCount(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedProbesAddress, null).await().body()
        }

        suspend fun getActiveProbes(vertx: Vertx): List<ActiveInstance> {
            return vertx.eventBus().request<List<ActiveInstance>>(activeProbesAddress, null).await().body()
        }
    }

    private val activeProbes: MutableMap<String, ActiveInstance> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.deployVerticle(ProbeGenerator(router)).await()

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
            val remote = it.body().getString("address")
            if (!remote.contains(":")) {
                val probeId = it.headers().get("probe_id")
                activeProbes[probeId]!!.meta.putIfAbsent("remotes", mutableListOf<String>())
                (activeProbes[probeId]!!.meta["remotes"] as MutableList<String>).add(remote)
                log.trace { Msg.msg("Probe {} registered {}", probeId, remote) }

                launch(vertx.dispatcher()) {
                    vertx.sharedData().getLocalCounter(remote).await()
                        .incrementAndGet().await()
                }
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_CONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { Msg.msg("Establishing connection with probe {}", conn.instanceId) }

            activeProbes[conn.instanceId] = ActiveInstance(conn.instanceId, System.currentTimeMillis(), conn.meta)
            it.reply(true)
            log.info("Probe connected. Latency: {}ms - Probes connected: {}", latency, activeProbes.size)

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

                (activeProbe.meta["remotes"] as List<String>?).orEmpty().forEach {
                    vertx.sharedData().getLocalCounter(it).await()
                        .decrementAndGet().await()
                }
            }
        }

        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                .addInboundPermitted(PermittedOptions().setAddressRegex("spp\\.platform\\.status\\..+"))
                .addInboundPermitted(PermittedOptions().setAddressRegex("spp\\.probe\\.status\\..+"))
                .addOutboundPermitted(PermittedOptions().setAddressRegex("spp\\.probe\\.command\\..+")),
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                if (it.rawMessage.getString("address") == PlatformAddress.PROBE_CONNECTED.address) {
                    val conn = Json.decodeValue(
                        it.rawMessage.getJsonObject("body").toString(), InstanceConnection::class.java
                    )
                    SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.instanceId)

                    it.socket().closeHandler { _ ->
                        vertx.eventBus().publish(
                            PlatformAddress.PROBE_DISCONNECTED.address,
                            it.rawMessage.getJsonObject("body")
                        )
                    }
                }

                //auto-add probe id to headers
                val probeId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (probeId != null && it.rawMessage.containsKey("headers")) {
                    it.rawMessage.getJsonObject("headers").put("probe_id", probeId)
                }
            } else if (it.type() == BridgeEventType.REGISTERED) {
                val probeId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (probeId != null) {
                    launch(vertx.dispatcher()) {
                        delay(1500) //todo: this is temp fix for race condition
                        vertx.eventBus().publish(
                            ProbeAddress.REMOTE_REGISTERED.address,
                            it.rawMessage,
                            DeliveryOptions().addHeader("probe_id", probeId)
                        )
                    }
                } else {
                    log.error("Failed to register remote due to missing probe id")
                    it.fail("Missing probe id")
                    return@create
                }
            }
            it.complete(true) //todo: validateAuth(it)
        }.listen(config.getString("bridge_port").toInt()).await()
    }
}
