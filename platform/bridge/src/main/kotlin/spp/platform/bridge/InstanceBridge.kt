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
package spp.platform.bridge

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.auth.authentication.TokenCredentials
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.kotlin.coroutines.CoroutineVerticle
import mu.KotlinLogging
import spp.platform.common.ClientAuth
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.ClientAccess
import java.util.concurrent.ConcurrentHashMap

abstract class InstanceBridge(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    protected val activeConnections = ConcurrentHashMap<String, ActiveConnection>()

    override suspend fun start() {
        //pong timeout handler
        vertx.setPeriodic(1000) {
            val now = System.currentTimeMillis()
            activeConnections.forEach { (connectionId, probe) ->
                if (probe.lastPing + 10000 < now) {
                    log.warn { "Connection {} timed out".args(connectionId) }
                    activeConnections.remove(connectionId)?.close()
                }
            }
        }

        setupBridges()
    }

    abstract suspend fun setupBridges()

    open fun handleBridgeEvent(event: BaseBridgeEvent) {
        if (event.type() == BridgeEventType.SOCKET_PING) {
            val activeConnection = activeConnections[getWriteHandlerID(event)]
            if (activeConnection == null) {
                log.error("Unknown connection pinged. Closing connection.")
                event.complete(false)
            } else {
                activeConnection.lastPing = System.currentTimeMillis()
                event.complete(true)
            }
        } else {
            event.complete(true)
        }
    }

    fun validateMarkerAuth(event: BaseBridgeEvent, handler: Handler<AsyncResult<DeveloperAuth>>) {
        if (jwtAuth != null) {
            val authToken = event.rawMessage.getJsonObject("headers")?.getString("auth-token")
            if (authToken.isNullOrEmpty()) {
                handler.handle(Future.failedFuture("Rejected ${event.type()} event with missing auth token"))
            } else {
                validateAuthToken(authToken) {
                    if (it.succeeded()) {
                        handler.handle(Future.succeededFuture(it.result()))
                    } else {
                        log.error(buildString {
                            append("Failed to authenticate ${event.type()} event")
                            append(". Address: ").append(event.rawMessage.getString("address"))
                            append(". Reason: ").append(it.cause().message)
                        })
                        handler.handle(Future.failedFuture((it.cause())))
                    }
                }
            }
        } else {
            val developerAuth = DeveloperAuth("system")
            Vertx.currentContext().putLocal("developer", developerAuth)
            handler.handle(Future.succeededFuture(developerAuth))
        }
    }

    fun validateProbeAuth(event: BaseBridgeEvent, handler: Handler<AsyncResult<ClientAuth>>) {
        val authEnabled = ClusterConnection.config.getJsonObject("client-access")?.getString("enabled")
            ?.toBooleanStrictOrNull()
        if (authEnabled == true) {
            val clientId = event.rawMessage.getJsonObject("headers")?.getString("client_id")
            val clientSecret = event.rawMessage.getJsonObject("headers")?.getString("client_secret")
            if (clientId == null || clientSecret == null) {
                handler.handle(Future.failedFuture("Rejected ${event.type()} event with missing client credentials"))
                return
            }
            val tenantId = event.rawMessage.getJsonObject("headers")?.getString("tenant_id")
            if (!tenantId.isNullOrEmpty()) {
                Vertx.currentContext().putLocal("tenant_id", tenantId)
            } else {
                Vertx.currentContext().removeLocal("tenant_id")
            }
            Vertx.currentContext().removeLocal("client")

            log.trace { "Validating client credentials. Client id: $clientId - Client secret: $clientSecret" }
            SourceStorage.isValidClientAccess(clientId, clientSecret).onSuccess {
                val clientAuth = ClientAuth(ClientAccess(clientId, clientSecret), tenantId)
                Vertx.currentContext().putLocal("client", clientAuth)
                handler.handle(Future.succeededFuture(clientAuth))
            }.onFailure {
                handler.handle(Future.failedFuture("Rejected ${event.type()} event with invalid client credentials"))
            }
        } else {
            handler.handle(Future.succeededFuture())
        }
    }

    fun validateAuthToken(authToken: String?, handler: Handler<AsyncResult<DeveloperAuth>>) {
        if (jwtAuth == null) {
            val developerAuth = DeveloperAuth("system")
            Vertx.currentContext().putLocal("developer", developerAuth)
            handler.handle(Future.succeededFuture(developerAuth))
            return
        }

        log.trace { "Validating auth token: $authToken" }
        jwtAuth.authenticate(TokenCredentials(authToken)) {
            if (it.succeeded()) {
                Vertx.currentContext().putLocal("user", it.result())
                val selfId = it.result().principal().getString("developer_id")
                val accessToken = it.result().principal().getString("access_token")
                val developerAuth = DeveloperAuth.from(selfId, accessToken)
                Vertx.currentContext().putLocal("developer", developerAuth)
                handler.handle(Future.succeededFuture(developerAuth))
            } else {
                handler.handle(Future.failedFuture((it.cause())))
            }
        }
    }

    fun getWriteHandlerID(event: BaseBridgeEvent): String {
        return when (event) {
            is io.vertx.ext.eventbus.bridge.tcp.BridgeEvent -> event.socket().writeHandlerID()
            is io.vertx.ext.web.handler.sockjs.BridgeEvent -> event.socket().writeHandlerID()
            else -> throw IllegalArgumentException("Unknown bridge event type")
        }
    }

    fun setCloseHandler(event: BaseBridgeEvent, handler: Handler<Void>) {
        when (event) {
            is io.vertx.ext.eventbus.bridge.tcp.BridgeEvent -> event.socket().closeHandler(handler)
            is io.vertx.ext.web.handler.sockjs.BridgeEvent -> event.socket().closeHandler(handler)
            else -> throw IllegalArgumentException("Unknown bridge event type")
        }
    }
}
