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

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.bridge.BridgeAddress
import spp.platform.bridge.InstanceBridge
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
            handleConnection(marker.body())
            marker.reply(true)
        }

        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from marker
                .addInboundPermitted(PermittedOptions().setAddress("get-records")) //todo: name like others
                .addInboundPermitted(PermittedOptions().setAddress(MARKER_CONNECTED))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_SERVICE))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_VIEW))
                //to marker
                .addOutboundPermitted(
                    PermittedOptions().setAddressRegex(Provide.LIVE_INSTRUMENT_SUBSCRIBER + "\\:.+")
                )
                .addOutboundPermitted(
                    PermittedOptions().setAddressRegex(Provide.LIVE_VIEW_SUBSCRIBER + "\\:.+")
                ),
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND && it.rawMessage.getString("address") == MARKER_CONNECTED) {
                launch(vertx.dispatcher()) {
                    validateAuth(it) { devAuth ->
                        if (devAuth.succeeded()) {
                            val rawConnectionBody = it.rawMessage.getJsonObject("body")
                            it.socket().closeHandler {
                                handleDisconnection(rawConnectionBody)
                                vertx.eventBus().publish(MARKER_DISCONNECTED, JsonObject.mapFrom(devAuth.result()))
                            }
                            it.complete(true)
                        } else {
                            it.fail(devAuth.cause().message)
                        }
                    }
                }
            } else {
                validateAuth(it)
            }
        }.listen(config.getString("bridge_port").toInt()).await()
    }

    private fun handleConnection(rawConnectionBody: JsonObject) {
        val conn = Json.decodeValue(rawConnectionBody.toString(), InstanceConnection::class.java)
        val latency = System.currentTimeMillis() - conn.connectionTime
        log.trace { "Establishing connection with marker ${conn.instanceId}" }

        val selfId = Vertx.currentContext().get<DeveloperAuth>("developer").selfId
        conn.meta["selfId"] = selfId

        val activeInstance = ActiveInstance(conn.instanceId, System.currentTimeMillis(), conn.meta)
        launch(vertx.dispatcher()) {
            val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_MARKERS)
            map.put(conn.instanceId, JsonObject.mapFrom(activeInstance)).onSuccess {
                map.size().onSuccess {
                    log.info("Marker connected. Latency: {}ms - Markers connected: {}", latency, it)
                }.onFailure {
                    log.error("Failed to get active markers", it)
                }
            }.onFailure {
                log.error("Failed to update active marker", it)
            }
        }

        launch(vertx.dispatcher()) {
            SourceStorage.counter(MARKER_CONNECTED).incrementAndGet().await()
        }
    }

    private fun handleDisconnection(rawConnectionBody: JsonObject) {
        val conn = Json.decodeValue(rawConnectionBody.toString(), InstanceConnection::class.java)
        launch(vertx.dispatcher()) {
            val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_MARKERS)
            map.remove(conn.instanceId).onSuccess {
                if (it != null) {
                    val connectedAt = Instant.ofEpochMilli(it.getLong("connectedAt"))
                    log.info("Marker disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

                    launch(vertx.dispatcher()) {
                        SourceStorage.counter(MARKER_CONNECTED).decrementAndGet().await()
                    }
                }
            }.onFailure {
                log.error("Failed to remove active marker", it)
            }
        }
    }
}
