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
package spp.platform.bridge.probe

import io.vertx.core.DeploymentOptions
import io.vertx.core.Handler
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.bridge.BridgeAddress
import spp.platform.bridge.InstanceBridge
import spp.platform.common.util.Msg
import spp.platform.storage.SourceStorage
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.PlatformAddress.PROBE_CONNECTED
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.ProcessorAddress
import spp.protocol.platform.status.ActiveInstance
import spp.protocol.platform.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Provides services for and tracking of Live Probes.
 */
class ProbeBridge(
    private val router: Router,
    jwtAuth: JWTAuth?,
    private val netServerOptions: NetServerOptions
) : InstanceBridge(jwtAuth) {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override suspend fun start() {
        vertx.deployVerticle(
            ProbeGenerator(router),
            DeploymentOptions()
                .setWorker(true).setWorkerPoolName("spp-probe-generator")
                .setMaxWorkerExecuteTime(5).setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES)
                .setConfig(config)
        ).await()

        vertx.eventBus().consumer<JsonObject>(ProcessorAddress.REMOTE_REGISTERED) {
            val remote = it.body().getString("address")
            if (!remote.contains(":")) {
                val probeId = it.headers().get("probe_id")
                launch(vertx.dispatcher()) {
                    val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_PROBES)
                    map.get(probeId).onSuccess {
                        val updatedActiveInstance = it
                        val remotes = updatedActiveInstance.getJsonObject("meta").getJsonArray("remotes")
                        if (remotes == null) {
                            updatedActiveInstance.getJsonObject("meta").put("remotes", JsonArray().add(remote))
                        } else {
                            remotes.add(remote)
                        }
                        map.put(probeId, updatedActiveInstance).onSuccess {
                            log.debug { Msg.msg("Probe {} registered {}", probeId, remote) }
                        }.onFailure {
                            log.error("Failed to update active probe", it)
                        }
                    }.onFailure {
                        log.error("Failed to get active probe for $probeId", it)
                    }
                }

                launch(vertx.dispatcher()) {
                    SourceStorage.counter(remote).incrementAndGet().await()
                }
            }
        }
        vertx.eventBus().consumer<JsonObject>(PROBE_CONNECTED) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.debug { Msg.msg("Establishing connection with probe {}", conn.instanceId) }

            val activeInstance = ActiveInstance(conn.instanceId, System.currentTimeMillis(), conn.meta)
            launch(vertx.dispatcher()) {
                val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_PROBES)
                map.put(conn.instanceId, JsonObject.mapFrom(activeInstance)).onSuccess {
                    map.size().onSuccess {
                        log.info("Probe connected. Latency: {}ms - Probes connected: {}", latency, it)
                    }.onFailure {
                        log.error("Failed to get active probes", it)
                    }
                }.onFailure {
                    log.error("Failed to update active probe", it)
                }
            }
            it.reply(true)

            launch(vertx.dispatcher()) {
                SourceStorage.counter(PROBE_CONNECTED).incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROBE_DISCONNECTED) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            launch(vertx.dispatcher()) {
                val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_PROBES)
                map.remove(conn.instanceId).onSuccess {
                    val activeProbe = it!!
                    val connectedAt = Instant.ofEpochMilli(activeProbe.getLong("connectedAt"))
                    log.info("Probe disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

                    launch(vertx.dispatcher()) {
                        SourceStorage.counter(PROBE_CONNECTED).decrementAndGet().await()

                        activeProbe.getJsonObject("meta").getJsonArray("remotes").forEach {
                            SourceStorage.counter(it.toString()).decrementAndGet().await()
                        }
                    }
                }.onFailure {
                    log.error("Failed to remove active probe", it)
                }
            }
        }

        val subscriberCache = ConcurrentHashMap<String, String>() //todo: use storage

        //http bridge
        val sockJSHandler = SockJSHandler.create(vertx, SockJSHandlerOptions().setRegisterWriteHandler(true))
        val portalBridgeOptions = SockJSBridgeOptions().apply {
            inboundPermitteds = getInboundPermitted() //from probe
            outboundPermitteds = getOutboundPermitted() //to probe
        }
        sockJSHandler.bridge(portalBridgeOptions) { handleBridgeEvent(it, subscriberCache) }
        router.route("/probe/eventbus/*").handler(sockJSHandler)

        //tcp bridge
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions().apply {
                inboundPermitteds = getInboundPermitted() //from probe
                outboundPermitteds = getOutboundPermitted() //to probe
            },
            netServerOptions
        ) { handleBridgeEvent(it, subscriberCache) }
            .listen(config.getJsonObject("probe").getString("bridge_port").toInt()).await()
    }

    private fun handleBridgeEvent(it: BaseBridgeEvent, subscriberCache: ConcurrentHashMap<String, String>) {
        if (it.type() == BridgeEventType.SEND) {
            val writeHandlerID = getWriteHandlerID(it)
            if (it.rawMessage.getString("address") == PROBE_CONNECTED) {
                val conn = Json.decodeValue(
                    it.rawMessage.getJsonObject("body").toString(), InstanceConnection::class.java
                )
                subscriberCache[writeHandlerID] = conn.instanceId

                setCloseHandler(it) { _ ->
                    vertx.eventBus().publish(
                        PlatformAddress.PROBE_DISCONNECTED,
                        it.rawMessage.getJsonObject("body")
                    )
                    subscriberCache.remove(writeHandlerID)
                }
            }

            //auto-add probe id to headers
            val probeId = subscriberCache[writeHandlerID]
            if (probeId != null && it.rawMessage.containsKey("headers")) {
                it.rawMessage.getJsonObject("headers").put("probe_id", probeId)
            }
        } else if (it.type() == BridgeEventType.REGISTERED) {
            val probeId = subscriberCache[getWriteHandlerID(it)]
            if (probeId != null) {
                launch(vertx.dispatcher()) {
                    delay(1500) //todo: this is temp fix for race condition
                    vertx.eventBus().publish(
                        ProcessorAddress.REMOTE_REGISTERED,
                        it.rawMessage,
                        DeliveryOptions().addHeader("probe_id", probeId)
                    )
                }
            } else {
                log.error("Failed to register remote due to missing probe id")
                it.fail("Missing probe id")
                return
            }
        }
        it.complete(true) //todo: validateAuth(it)
    }

    private fun setCloseHandler(event: BaseBridgeEvent, handler: Handler<Void>) {
        when (event) {
            is io.vertx.ext.eventbus.bridge.tcp.BridgeEvent -> event.socket().closeHandler(handler)
            is io.vertx.ext.web.handler.sockjs.BridgeEvent -> event.socket().closeHandler(handler)
            else -> throw IllegalArgumentException("Unknown bridge event type")
        }
    }

    private fun getWriteHandlerID(event: BaseBridgeEvent): String {
        return when (event) {
            is io.vertx.ext.eventbus.bridge.tcp.BridgeEvent -> event.socket().writeHandlerID()
            is io.vertx.ext.web.handler.sockjs.BridgeEvent -> event.socket().writeHandlerID()
            else -> throw IllegalArgumentException("Unknown bridge event type")
        }
    }

    private fun getInboundPermitted(): List<PermittedOptions> {
        return listOf(
            PermittedOptions().setAddress(PROBE_CONNECTED),
            PermittedOptions().setAddress(ProcessorAddress.REMOTE_REGISTERED),
            PermittedOptions().setAddress(ProcessorAddress.LIVE_INSTRUMENT_APPLIED),
            PermittedOptions().setAddress(ProcessorAddress.LIVE_INSTRUMENT_REMOVED)
        )
    }

    private fun getOutboundPermitted(): List<PermittedOptions> {
        return listOf(
            PermittedOptions().setAddressRegex(ProbeAddress.LIVE_INSTRUMENT_REMOTE),
            PermittedOptions().setAddressRegex(ProbeAddress.LIVE_INSTRUMENT_REMOTE + "\\:.+")
        )
    }
}
