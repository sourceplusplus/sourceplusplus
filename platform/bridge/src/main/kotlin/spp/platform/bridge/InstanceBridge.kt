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
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.kotlin.coroutines.CoroutineVerticle
import mu.KotlinLogging
import spp.platform.common.ClientAuth
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.ClientAccess

abstract class InstanceBridge(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun validateMarkerAuth(event: BaseBridgeEvent) {
        validateMarkerAuth(event) {
            if (it.succeeded()) {
                event.complete(true)
            } else {
                log.error("Failed to validate marker auth. Reason: ${it.cause().message}")
                event.complete(false)
            }
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
                        log.warn("Failed to authenticate ${event.type()} event. Reason: ${it.cause().message}")
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
        jwtAuth.authenticate(JsonObject().put("token", authToken)) {
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
}
