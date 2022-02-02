package spp.platform.core

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
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
                event.fail("Rejected ${event.type()} event with missing auth token")
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
