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
package spp.platform.bridge

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import spp.platform.common.DeveloperAuth

abstract class InstanceBridge(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(InstanceBridge::class.java)
    }

    fun validateAuth(event: BaseBridgeEvent) {
        validateAuth(event) {
            if (it.succeeded()) {
                event.complete(true)
            } else {
                event.fail(it.cause().message)
            }
        }
    }

    fun validateAuth(event: BaseBridgeEvent, handler: Handler<AsyncResult<DeveloperAuth>>) {
        if (jwtAuth != null) {
            val authToken = event.rawMessage.getJsonObject("headers")?.getString("auth-token")
            if (authToken.isNullOrEmpty()) {
                if (event.type() == BridgeEventType.SEND) {
                    handler.handle(
                        Future.failedFuture(
                            "Rejected SEND event to ${event.rawMessage.getString("address")} with missing auth token"
                        )
                    )
                } else {
                    handler.handle(Future.failedFuture("Rejected ${event.type()} event with missing auth token"))
                }
                return
            }

            validateAuthToken(authToken) {
                if (it.succeeded()) {
                    handler.handle(Future.succeededFuture(it.result()))
                } else {
                    log.warn("Failed to authenticate ${event.type()} event", it.cause())
                    handler.handle(Future.failedFuture((it.cause())))
                }
            }
        } else {
            val developerAuth = DeveloperAuth("system")
            Vertx.currentContext().put("developer", developerAuth)
            handler.handle(Future.succeededFuture(developerAuth))
        }
    }

    fun validateAuthToken(authToken: String?, handler: Handler<AsyncResult<DeveloperAuth>>) {
        if (jwtAuth == null) {
            val developerAuth = DeveloperAuth("system")
            Vertx.currentContext().put("developer", developerAuth)
            handler.handle(Future.succeededFuture(developerAuth))
            return
        }

        jwtAuth.authenticate(JsonObject().put("token", authToken)) {
            if (it.succeeded()) {
                Vertx.currentContext().put("user", it.result())
                val selfId = it.result().principal().getString("developer_id")
                val accessToken = it.result().principal().getString("access_token")
                val developerAuth = DeveloperAuth.from(selfId, accessToken)
                Vertx.currentContext().put("developer", developerAuth)
                handler.handle(Future.succeededFuture(developerAuth))
            } else {
                handler.handle(Future.failedFuture((it.cause())))
            }
        }
    }
}
