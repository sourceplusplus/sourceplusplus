/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
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
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.core.shareddata.AsyncMap
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.ext.bridge.BridgeEventType.*
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.bridge.ActiveConnection
import spp.platform.bridge.BridgeAddress
import spp.platform.bridge.InstanceBridge
import spp.platform.common.ClientAuth
import spp.platform.common.ClusterConnection
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.protocol.platform.PlatformAddress.PROBE_CONNECTED
import spp.protocol.platform.PlatformAddress.PROBE_DISCONNECTED
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.ProcessorAddress
import spp.protocol.platform.auth.ClientAccess
import spp.protocol.platform.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Provides services for and tracking of Live Probes.
 */
class ProbeBridge(
    private val router: Router,
    jwtAuth: JWTAuth?
) : InstanceBridge(jwtAuth) {

    companion object {
        private val log = KotlinLogging.logger {}
        private val PING_MESSAGE = Buffer.buffer("\u0000\u0000\u0000\u0010{\"type\": \"ping\"}".toByteArray())

        suspend fun getActiveProbesMap(): AsyncMap<String, JsonObject> {
            return SourceStorage.map(BridgeAddress.ACTIVE_PROBES)
        }
    }

    private val inboundPermitted = listOf(
        PermittedOptions().setAddress(PROBE_CONNECTED),
        PermittedOptions().setAddress(ProcessorAddress.REMOTE_REGISTERED),
        PermittedOptions().setAddress(ProcessorAddress.LIVE_INSTRUMENT_APPLIED),
        PermittedOptions().setAddress(ProcessorAddress.LIVE_INSTRUMENT_REMOVED)
    )

    private val outboundPermitted = listOf(
        PermittedOptions().setAddressRegex(ProbeAddress.LIVE_INSTRUMENT_REMOTE + "\\:.+")
    )

    override suspend fun start() {
        super.start()
        vertx.eventBus().consumer(PROBE_CONNECTED, ::handleConnection)
        vertx.eventBus().consumer(PROBE_DISCONNECTED, ::handleDisconnection)
        vertx.eventBus().consumer(ProcessorAddress.REMOTE_REGISTERED, ::handleRemoteRegistered)

        vertx.deployVerticle(
            ProbeGenerator(router),
            DeploymentOptions()
                .setWorker(true).setWorkerPoolName("spp-probe-generator")
                .setMaxWorkerExecuteTime(5).setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES)
                .setConfig(config)
        ).await()
    }

    override suspend fun setupBridges() {
        //http bridge
        val sockJSHandler = SockJSHandler.create(vertx, SockJSHandlerOptions().setRegisterWriteHandler(true))
        val portalBridgeOptions = SockJSBridgeOptions().apply {
            inboundPermitteds = inboundPermitted //from probe
            outboundPermitteds = outboundPermitted //to probe
        }
        router.route("/probe/eventbus/*")
            .subRouter(sockJSHandler.bridge(portalBridgeOptions) { handleBridgeEvent(it) })

        //tcp bridge
        val bridge = TcpEventBusBridge.create(
            vertx,
            BridgeOptions().apply {
                inboundPermitteds = inboundPermitted //from probe
                outboundPermitteds = outboundPermitted //to probe
            },
            NetServerOptions()
        ) { handleBridgeEvent(it) }.listen(0)
        ClusterConnection.multiUseNetServer.addUse(bridge) {
            //Python probes may send ping as first message.
            //If first message is ping, assume it's a probe connection.
            it.toString().contains(PROBE_CONNECTED) || it == PING_MESSAGE
        }
    }

    private fun handleConnection(it: Message<JsonObject>) {
        val connectionTime = System.currentTimeMillis()
        val conn = InstanceConnection(it.body())
        val latency = connectionTime - conn.connectionTime
        log.debug { "Establishing connection with probe {}".args(conn.instanceId) }

        launch(vertx.dispatcher()) {
            val map = getActiveProbesMap()
            map.put(conn.instanceId, JsonObject.mapFrom(conn.copy(connectionTime = connectionTime))).onSuccess {
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

    private fun handleDisconnection(it: Message<JsonObject>) {
        val conn = InstanceConnection(it.body())
        launch(vertx.dispatcher()) {
            val map = getActiveProbesMap()
            val activeProbe = map.remove(conn.instanceId).await()
            val connectionTime = Instant.ofEpochMilli(activeProbe.getLong("connectionTime"))
            val connectionDuration = Duration.between(Instant.now(), connectionTime)
            val probesRemaining = SourceStorage.counter(PROBE_CONNECTED).decrementAndGet().await()
            log.info("Probe disconnected. Connection time: {} - Remaining: {}", connectionDuration, probesRemaining)

            activeProbe.getJsonObject("meta").getJsonArray("remotes")?.forEach {
                SourceStorage.counter(it.toString()).decrementAndGet().await()
            }
        }
    }

    private fun handleRemoteRegistered(it: Message<JsonObject>) {
        val remote = it.body().getString("address").substringBefore(":")
        val probeId = it.headers().get("probe_id")
        log.trace { "Probe {} registering remote: {}".args(probeId, remote) }

        val clientAuth: ClientAuth? = it.headers().get("client_auth")?.let {
            ClientAuth.from(it)
        }
        if (clientAuth != null) {
            log.trace { "Using client auth: {}".args(clientAuth) }
            Vertx.currentContext().putLocal("client", clientAuth)
        }

        launch(vertx.dispatcher()) {
            val map = getActiveProbesMap()
            map.get(probeId).onSuccess { updatedInstanceConnection ->
                val remotes = updatedInstanceConnection.getJsonObject("meta").getJsonArray("remotes")
                if (remotes == null) {
                    updatedInstanceConnection.getJsonObject("meta").put("remotes", JsonArray().add(remote))
                } else {
                    remotes.add(remote)
                }
                map.put(probeId, updatedInstanceConnection).onSuccess {
                    log.debug { "Probe {} registered {}".args(probeId, remote) }
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

    override fun handleBridgeEvent(event: BaseBridgeEvent) {
        if (event.type() == REGISTERED) {
            val activeConnection = activeConnections[getWriteHandlerID(event)]
            if (activeConnection != null) {
                val deliveryOptions = DeliveryOptions()
                    .addHeader("probe_id", activeConnection.id)
                    .apply {
                        //create ClientAuth without validation as REGISTER event will have validated already
                        val clientId = event.rawMessage.getJsonObject("headers")?.getString("client_id")
                        val clientSecret = event.rawMessage.getJsonObject("headers")?.getString("client_secret")
                        val tenantId = event.rawMessage.getJsonObject("headers")?.getString("tenant_id")
                        if (clientId != null && clientSecret != null) {
                            val clientAccess = ClientAccess(clientId, clientSecret)
                            addHeader("client_auth", Json.encode(ClientAuth(clientAccess, tenantId)))
                        }
                    }
                vertx.eventBus().publish(ProcessorAddress.REMOTE_REGISTERED, event.rawMessage, deliveryOptions)
            } else {
                log.error("Failed to register remote due to missing probe id")
                event.fail("Missing probe id")
            }
        } else if (event.type() == SEND || event.type() == PUBLISH || event.type() == REGISTER) {
            validateProbeAuth(event) { clientAuth ->
                if (clientAuth.succeeded()) {
                    val writeHandlerID = getWriteHandlerID(event)
                    if (event.rawMessage.getString("address") == PROBE_CONNECTED) {
                        val conn = InstanceConnection(event.rawMessage.getJsonObject("body"))
                        activeConnections[writeHandlerID] = ActiveConnection.from(event).apply {
                            id = conn.instanceId
                        }

                        setCloseHandler(event) { _ ->
                            vertx.eventBus().publish(
                                PROBE_DISCONNECTED,
                                event.rawMessage.getJsonObject("body"),
                                DeliveryOptions().apply {
                                    event.rawMessage.getJsonObject("headers")?.let {
                                        it.map.forEach { (k, v) ->
                                            addHeader(k, v.toString())
                                        }
                                    }
                                }
                            )
                            activeConnections.remove(writeHandlerID)
                        }
                    }

                    //auto-add probe id to headers
                    val activeConnection = activeConnections[writeHandlerID]
                    if (activeConnection != null && event.rawMessage.containsKey("headers")) {
                        event.rawMessage.getJsonObject("headers").put("probe_id", activeConnection.id)
                    }
                    event.complete(true)
                } else {
                    log.error("Failed to validate probe auth. Reason: ${clientAuth.cause().message}")
                    event.complete(false)
                }
            }
        } else {
            super.handleBridgeEvent(event)
        }
    }
}
