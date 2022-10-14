/*
 * Source++, the continuous feedback platform for developers.
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
package spp.platform.common

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.impl.jose.JWT
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.serviceproxy.ServiceInterceptor
import org.apache.skywalking.oap.server.library.module.ModuleManager
import spp.protocol.platform.PlatformAddress.PROCESSOR_CONNECTED
import spp.protocol.platform.status.InstanceConnection
import java.util.*

abstract class FeedbackProcessor : CoroutineVerticle() {

    companion object {
        val INSTANCE_ID = UUID.randomUUID().toString()
        var module: ModuleManager? = null
    }

    var processorVerticleId: String? = null

    abstract fun bootProcessor(moduleManager: ModuleManager)

    abstract fun onConnected(vertx: Vertx)

    fun connectToPlatform() {
        val vertx = ClusterConnection.getVertx()

        //send processor connected status
        val pc = InstanceConnection(INSTANCE_ID, System.currentTimeMillis())
        vertx.eventBus().publish(PROCESSOR_CONNECTED, JsonObject.mapFrom(pc))
        onConnected(vertx)
    }

    fun developerAuthInterceptor(): ServiceInterceptor {
        return ServiceInterceptor { _, _, msg ->
            Vertx.currentContext().putLocal("developer", msg.headers().let {
                if (it.contains("auth-token")) {
                    DeveloperAuth(
                        JWT.parse(it.get("auth-token")).getJsonObject("payload").getString("developer_id"),
                        it.get("auth-token")
                    )
                } else DeveloperAuth("system", null)
            })
            Future.succeededFuture(msg)
        }
    }
}
