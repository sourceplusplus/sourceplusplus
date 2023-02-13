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
package spp.platform.bridge.marker

import io.vertx.core.Vertx
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.core.shareddata.AsyncMap
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.ext.bridge.BridgeEventType.*
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
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
import spp.platform.common.ClusterConnection
import spp.platform.common.ClusterConnection.router
import spp.platform.common.DeveloperAuth
import spp.platform.storage.SourceStorage
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.PlatformAddress.MARKER_CONNECTED
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.SourceServices.LIVE_INSIGHT
import spp.protocol.service.SourceServices.LIVE_INSTRUMENT
import spp.protocol.service.SourceServices.LIVE_MANAGEMENT
import spp.protocol.service.SourceServices.LIVE_VIEW
import spp.protocol.service.SourceServices.Subscribe
import java.time.Duration
import java.time.Instant

/**
 * Provides services for and tracking of Live Plugins.
 *
 * todo: rename Marker to Plugin?
 */
class MarkerBridge(
    jwtAuth: JWTAuth?
) : InstanceBridge(jwtAuth) {

    companion object {
        private val log = KotlinLogging.logger {}

        suspend fun getActiveMarkersMap(): AsyncMap<String, JsonObject> {
            return SourceStorage.map(BridgeAddress.ACTIVE_MARKERS)
        }
    }

    private val inboundPermitted = listOf(
        PermittedOptions().setAddress("get-records"),
        PermittedOptions().setAddress(MARKER_CONNECTED),
        PermittedOptions().setAddress(LIVE_MANAGEMENT),
        PermittedOptions().setAddress(LIVE_INSTRUMENT),
        PermittedOptions().setAddress(LIVE_VIEW),
        PermittedOptions().setAddress(LIVE_INSIGHT)
    )
    private val outboundPermitted = listOf(
        PermittedOptions().setAddressRegex(Subscribe.LIVE_INSTRUMENT_SUBSCRIBER + "\\:.+"),
        PermittedOptions().setAddressRegex(Subscribe.LIVE_VIEW_SUBSCRIBER + "\\:.+")
    )

    override suspend fun start() {
        super.start()
        vertx.eventBus().consumer(MARKER_CONNECTED, ::handleConnection)
    }

    override suspend fun setupBridges() {
        //http bridge
        val sockJSHandler = SockJSHandler.create(vertx, SockJSHandlerOptions().setRegisterWriteHandler(true))
        val portalBridgeOptions = SockJSBridgeOptions().apply {
            inboundPermitteds = inboundPermitted //from marker
            outboundPermitteds = outboundPermitted //to marker
        }
        router.route("/marker/eventbus/*")
            .subRouter(sockJSHandler.bridge(portalBridgeOptions) { handleBridgeEvent(it) })

        //tcp bridge
        val bridge = TcpEventBusBridge.create(
            vertx,
            BridgeOptions().apply {
                inboundPermitteds = inboundPermitted //from marker
                outboundPermitteds = outboundPermitted //to marker
            },
            NetServerOptions()
        ) { handleBridgeEvent(it) }.listen(0)
        ClusterConnection.multiUseNetServer.addUse(bridge) {
            it.toString().contains(MARKER_CONNECTED)
        }
    }

    private fun handleConnection(marker: Message<JsonObject>) {
        if (Vertx.currentContext().getLocal<DeveloperAuth>("developer") == null) {
            //todo: SockJS connections needs to revalidate for some reason
            val authToken = marker.headers()?.get("auth-token")
            validateAuthToken(authToken) {
                if (it.succeeded()) {
                    handleConnection(marker.body())
                    marker.reply(true)
                } else {
                    //todo: terminate connection
                    marker.reply(false)
                }
            }
        } else {
            handleConnection(marker.body())
            marker.reply(true)
        }
    }

    private fun handleConnection(rawConnectionBody: JsonObject) {
        val connectionTime = System.currentTimeMillis()
        val conn = InstanceConnection(rawConnectionBody)
        val latency = connectionTime - conn.connectionTime
        log.trace { "Establishing connection with marker ${conn.instanceId}" }

        val selfId = Vertx.currentContext().getLocal<DeveloperAuth>("developer").selfId
        conn.meta["selfId"] = selfId

        launch(vertx.dispatcher()) {
            val map = getActiveMarkersMap()
            map.put(conn.instanceId, JsonObject.mapFrom(conn.copy(connectionTime = connectionTime))).await()
            log.info("Marker connected. Latency: {}ms - Markers connected: {}", latency, map.size().await())

            SourceStorage.counter(MARKER_CONNECTED).incrementAndGet().await()
        }
    }

    private fun handleDisconnection(conn: InstanceConnection) {
        launch(vertx.dispatcher()) {
            val map = getActiveMarkersMap()
            val activeMarker = map.remove(conn.instanceId).await()
            val connectionTime = Instant.ofEpochMilli(activeMarker.getLong("connectionTime"))
            val connectionDuration = Duration.between(Instant.now(), connectionTime)
            val markersRemaining = SourceStorage.counter(MARKER_CONNECTED).decrementAndGet().await()
            log.info("Marker disconnected. Connection time: {} - Remaining: {}", connectionDuration, markersRemaining)
        }
    }

    override fun handleBridgeEvent(event: BaseBridgeEvent) {
        if (event.type() == SEND || event.type() == PUBLISH || event.type() == REGISTER) {
            validateMarkerAuth(event) { clientAuth ->
                if (clientAuth.succeeded()) {
                    val writeHandlerID = getWriteHandlerID(event)
                    if (event.rawMessage.getString("address") == MARKER_CONNECTED) {
                        val conn = InstanceConnection(event.rawMessage.getJsonObject("body"))
                        activeConnections[writeHandlerID] = ActiveConnection.from(event).apply {
                            id = conn.instanceId
                        }

                        setCloseHandler(event) { _ ->
                            handleDisconnection(conn)
                            vertx.eventBus().publish(
                                PlatformAddress.MARKER_DISCONNECTED,
                                JsonObject.mapFrom(clientAuth.result()),
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

                    //auto-add marker id to headers
                    val activeConnection = activeConnections[writeHandlerID]
                    if (activeConnection != null && event.rawMessage.containsKey("headers")) {
                        event.rawMessage.getJsonObject("headers").put("marker_id", activeConnection.id)
                    }
                    event.complete(true)
                } else {
                    log.error("Failed to validate connection. Reason: ${clientAuth.cause().message}")
                    event.complete(false)
                }
            }
        } else {
            super.handleBridgeEvent(event)
        }
    }
}
