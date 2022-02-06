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
package spp.platform.core

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.BridgeEvent
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory
import spp.processor.common.DeveloperAuth

abstract class InstanceBridge(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(InstanceBridge::class.java)
    }

    fun validateAuth(event: BridgeEvent) {
        if (jwtAuth != null) {
            val authToken = event.rawMessage.getJsonObject("headers").getString("auth-token")
            if (authToken.isNullOrEmpty()) {
                if (event.type() == BridgeEventType.SEND) {
                    event.fail("Rejected SEND event to ${event.rawMessage.getString("address")} with missing auth token")
                } else {
                    event.fail("Rejected ${event.type()} event with missing auth token")
                }
                return
            }

            jwtAuth.authenticate(JsonObject().put("token", authToken)) {
                if (it.succeeded()) {
                    Vertx.currentContext().put("user", it.result())
                    val selfId = it.result().principal().getString("developer_id")
                    val accessToken = it.result().principal().getString("access_token")
                    Vertx.currentContext().put("developer", DeveloperAuth.from(selfId, accessToken))
                    event.complete(true)
                } else {
                    log.warn("Failed to authenticate ${event.type()} event", it.cause())
                    event.fail(it.cause())
                }
            }
        } else {
            Vertx.currentContext().put("developer", DeveloperAuth("system"))
            event.complete(true)
        }
    }
}
