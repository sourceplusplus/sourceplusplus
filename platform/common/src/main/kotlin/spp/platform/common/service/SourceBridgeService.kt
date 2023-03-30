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
package spp.platform.common.service

import io.vertx.codegen.annotations.GenIgnore
import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.impl.ContextInternal
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.PlatformServices.BRIDGE_SERVICE

@ProxyGen
@VertxGen
interface SourceBridgeService {

    @GenIgnore
    companion object {
        private val log = KotlinLogging.logger {}

        @GenIgnore
        @JvmStatic
        fun createProxy(vertx: Vertx, accessToken: String? = null): Future<SourceBridgeService?> {
            log.trace { "Getting SourceBridgeService" }
            val promise = Promise.promise<SourceBridgeService?>()
            discovery.getRecord(JsonObject().put("name", BRIDGE_SERVICE)).onComplete {
                if (it.succeeded()) {
                    if (it.result() == null) {
                        log.trace { "SourceBridgeService not found" }
                        promise.complete(null)
                    } else {
                        log.trace { "SourceBridgeService found" }
                        val deliveryOptions = DeliveryOptions().apply {
                            accessToken?.let { addHeader("auth-token", it) }
                            (Vertx.currentContext() as? ContextInternal)?.localContextData()?.forEach {
                                addHeader(it.key.toString(), it.value.toString())
                            }
                        }
                        promise.complete(SourceBridgeServiceVertxEBProxy(vertx, BRIDGE_SERVICE, deliveryOptions))
                    }
                } else {
                    promise.fail(it.cause())
                }
            }
            return promise.future()
        }
    }

    fun getActiveMarkers(): Future<JsonArray>
    fun getActiveProbes(): Future<JsonArray>
    fun getConnectedMarkers(): Future<Int>
    fun getConnectedProbes(): Future<Int>
    fun updateActiveProbeMetadata(id: String, metadata: JsonObject): Future<JsonObject>
}
