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
package spp.platform.marker

import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.protocol.SourceServices.Provide
import spp.protocol.SourceServices.Utilize
import spp.protocol.platform.PlatformAddress
import spp.protocol.status.ActiveMarker
import spp.protocol.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class MarkerBridge(
    private val jwtAuth: JWTAuth?,
    private val netServerOptions: NetServerOptions
) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}

        private const val connectedMarkersAddress = "get-connected-markers"
        private const val activeMarkersAddress = "get-active-markers"

        suspend fun getConnectedMarkerCount(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedMarkersAddress, null).await().body()
        }

        suspend fun getActiveMarkers(vertx: Vertx): List<ActiveMarker> {
            return vertx.eventBus().request<List<ActiveMarker>>(activeMarkersAddress, null).await().body()
        }
    }

    private val activeMarkers: MutableMap<String, ActiveMarker> = ConcurrentHashMap()

    private fun addActiveMarker(selfId: String, conn: InstanceConnection, marker: Message<JsonObject>, latency: Long) {
        activeMarkers[conn.instanceId] =
            ActiveMarker(conn.instanceId, System.currentTimeMillis(), selfId, meta = conn.meta)
        marker.reply(true)

        log.info(
            "Marker connected. Latency: {}ms - Active markers: {}",
            latency, activeMarkers.size
        )

        launch(vertx.dispatcher()) {
            vertx.sharedData().getLocalCounter(PlatformAddress.MARKER_CONNECTED.address).await()
                .incrementAndGet().await()
        }
    }

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(activeMarkersAddress) {
            launch(vertx.dispatcher()) {
                it.reply(ArrayList(activeMarkers.values))
            }
        }
        vertx.eventBus().consumer<JsonObject>(connectedMarkersAddress) {
            launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        PlatformAddress.MARKER_CONNECTED.address
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.MARKER_CONNECTED.address) { marker ->
            val conn = Json.decodeValue(marker.body().toString(), InstanceConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { "Establishing connection with marker ${conn.instanceId}" }

            if (jwtAuth != null && !marker.headers().get("auth-token").isNullOrEmpty()) {
                jwtAuth.authenticate(JsonObject().put("token", marker.headers().get("auth-token"))).onComplete {
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
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val activeMarker = activeMarkers.remove(conn.instanceId)
            if (activeMarker != null) {
                val connectedAt = Instant.ofEpochMilli(activeMarker.connectedAt)
                log.info("Marker disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

                launch(vertx.dispatcher()) {
                    vertx.sharedData().getLocalCounter(PlatformAddress.MARKER_CONNECTED.address).await()
                        .decrementAndGet().await()
                }
            }
        }

        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from marker
                .addInboundPermitted(PermittedOptions().setAddress("get-records")) //todo: name like others
                .addInboundPermitted(PermittedOptions().setAddress(PlatformAddress.MARKER_CONNECTED.address))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_SERVICE))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_VIEW))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LOG_COUNT_INDICATOR))
                //to marker
                .addOutboundPermitted(PermittedOptions().setAddress(Provide.LIVE_INSTRUMENT_SUBSCRIBER))
                .addOutboundPermitted(
                    PermittedOptions().setAddressRegex(Provide.LIVE_VIEW_SUBSCRIBER + "\\..+")
                ),
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                if (it.rawMessage.getString("address") == PlatformAddress.MARKER_CONNECTED.address) {
                    launch(vertx.dispatcher()) {
                        it.socket().closeHandler { _ ->
                            vertx.eventBus().publish(
                                PlatformAddress.MARKER_DISCONNECTED.address,
                                it.rawMessage.getJsonObject("body")
                            )
                        }
                    }
                }
            }
            it.complete(true)
        }.listen(config.getString("bridge_port").toInt()).await()
    }
}
