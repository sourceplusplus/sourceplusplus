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
package spp.platform.bridge.marker

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
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
import spp.platform.bridge.BridgeAddress
import spp.platform.bridge.InstanceBridge
import spp.platform.common.ClusterConnection.router
import spp.platform.common.DeveloperAuth
import spp.platform.storage.SourceStorage
import spp.protocol.SourceServices.Provide
import spp.protocol.SourceServices.Utilize
import spp.protocol.platform.PlatformAddress.MARKER_CONNECTED
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.platform.status.ActiveInstance
import spp.protocol.platform.status.InstanceConnection
import java.time.Duration
import java.time.Instant

/**
 * Provides services for and tracking of Live Plugins.
 *
 * todo: rename Marker to Plugin?
 */
class MarkerBridge(
    jwtAuth: JWTAuth?,
    private val netServerOptions: NetServerOptions
) : InstanceBridge(jwtAuth) {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(MARKER_CONNECTED) { marker ->
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

        //http bridge
        val sockJSHandler = SockJSHandler.create(vertx, SockJSHandlerOptions().setRegisterWriteHandler(true))
        val portalBridgeOptions = SockJSBridgeOptions().apply {
            inboundPermitteds = getInboundPermitted() //from marker
            outboundPermitteds = getOutboundPermitted() //to marker
        }
        sockJSHandler.bridge(portalBridgeOptions) { handleBridgeEvent(it) }
        router.route("/marker/eventbus/*").handler(sockJSHandler)

        //tcp bridge
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions().apply {
                inboundPermitteds = getInboundPermitted() //from marker
                outboundPermitteds = getOutboundPermitted() //to marker
            },
            netServerOptions
        ) { handleBridgeEvent(it) }
            .listen(config.getString("bridge_port").toInt()).await()
    }

    private fun handleBridgeEvent(it: BaseBridgeEvent) {
        if (it.type() == SEND && it.rawMessage.getString("address") == MARKER_CONNECTED) {
            validateMarkerAuth(it) { devAuth ->
                if (devAuth.succeeded()) {
                    val rawConnectionBody = it.rawMessage.getJsonObject("body")
                    setCloseHandler(it) {
                        handleDisconnection(rawConnectionBody)
                        vertx.eventBus().publish(MARKER_DISCONNECTED, JsonObject.mapFrom(devAuth.result()))
                    }
                    it.complete(true)
                } else {
                    it.fail(devAuth.cause().message)
                }
            }
        } else if (it.type() == SEND || it.type() == PUBLISH || it.type() == REGISTER) {
            validateMarkerAuth(it)
        } else {
            it.complete(true)
        }
    }

    private fun setCloseHandler(event: BaseBridgeEvent, handler: Handler<Void>) {
        when (event) {
            is io.vertx.ext.eventbus.bridge.tcp.BridgeEvent -> event.socket().closeHandler(handler)
            is io.vertx.ext.web.handler.sockjs.BridgeEvent -> event.socket().closeHandler(handler)
            else -> throw IllegalArgumentException("Unknown bridge event type")
        }
    }

    private fun getInboundPermitted(): List<PermittedOptions> {
        return listOf(
            PermittedOptions().setAddress("get-records"),
            PermittedOptions().setAddress(MARKER_CONNECTED),
            PermittedOptions().setAddress(Utilize.LIVE_SERVICE),
            PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT),
            PermittedOptions().setAddress(Utilize.LIVE_VIEW)
        )
    }

    private fun getOutboundPermitted(): List<PermittedOptions> {
        return listOf(
            PermittedOptions().setAddressRegex(Provide.LIVE_INSTRUMENT_SUBSCRIBER + "\\:.+"),
            PermittedOptions().setAddressRegex(Provide.LIVE_VIEW_SUBSCRIBER + "\\:.+")
        )
    }

    private fun handleConnection(rawConnectionBody: JsonObject) {
        val conn = Json.decodeValue(rawConnectionBody.toString(), InstanceConnection::class.java)
        val latency = System.currentTimeMillis() - conn.connectionTime
        log.trace { "Establishing connection with marker ${conn.instanceId}" }

        val selfId = Vertx.currentContext().getLocal<DeveloperAuth>("developer").selfId
        conn.meta["selfId"] = selfId

        val activeInstance = ActiveInstance(conn.instanceId, System.currentTimeMillis(), conn.meta)
        launch(vertx.dispatcher()) {
            val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_MARKERS)
            map.put(conn.instanceId, JsonObject.mapFrom(activeInstance)).await()
            log.info("Marker connected. Latency: {}ms - Markers connected: {}", latency, map.size().await())

            SourceStorage.counter(MARKER_CONNECTED).incrementAndGet().await()
        }
    }

    private fun handleDisconnection(rawConnectionBody: JsonObject) {
        val conn = Json.decodeValue(rawConnectionBody.toString(), InstanceConnection::class.java)
        launch(vertx.dispatcher()) {
            val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_MARKERS)
            val it = map.remove(conn.instanceId).await()
            val connectedAt = Instant.ofEpochMilli(it.getLong("connectedAt"))
            log.info("Marker disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

            SourceStorage.counter(MARKER_CONNECTED).decrementAndGet().await()
        }
    }
}
