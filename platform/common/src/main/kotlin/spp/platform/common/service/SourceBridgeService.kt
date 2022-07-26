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
package spp.platform.common.service

import io.vertx.codegen.annotations.GenIgnore
import io.vertx.codegen.annotations.ProxyGen
import io.vertx.codegen.annotations.VertxGen
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.PlatformServices

@ProxyGen
@VertxGen
interface SourceBridgeService {

    @GenIgnore
    companion object {
        @GenIgnore
        @JvmStatic
        fun service(vertx: Vertx): Future<SourceBridgeService?> {
            val promise = Promise.promise<SourceBridgeService?>()
            discovery.getRecord(JsonObject().put("name", PlatformServices.BRIDGE_SERVICE)).onComplete {
                if (it.succeeded()) {
                    if (it.result() == null) {
                        promise.complete(null)
                    } else {
                        promise.complete(SourceBridgeServiceVertxEBProxy(vertx, PlatformServices.BRIDGE_SERVICE))
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
}
