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

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import spp.platform.bridge.marker.MarkerBridge
import spp.platform.bridge.probe.ProbeBridge
import spp.platform.common.service.SourceBridgeService

class SourceBridge : CoroutineVerticle(), SourceBridgeService {

    override fun getActiveMarkers(): Future<JsonArray> {
        val promise = Promise.promise<JsonArray>()
        launch(vertx.dispatcher()) {
            val jsonArray = JsonArray()
            MarkerBridge.getActiveMarkers(vertx).map {
                jsonArray.add(JsonObject.mapFrom(it))
            }
            promise.complete(jsonArray)
        }
        return promise.future()
    }

    override fun getActiveProbes(): Future<JsonArray> {
        val promise = Promise.promise<JsonArray>()
        launch(vertx.dispatcher()) {
            val jsonArray = JsonArray()
            ProbeBridge.getActiveProbes(vertx).map {
                jsonArray.add(JsonObject.mapFrom(it))
            }
            promise.complete(jsonArray)
        }
        return promise.future()
    }

    override fun getConnectedMarkers(): Future<Int> {
        val promise = Promise.promise<Int>()
        launch(vertx.dispatcher()) {
            promise.complete(MarkerBridge.getConnectedMarkerCount(vertx))
        }
        return promise.future()
    }

    override fun getConnectedProbes(): Future<Int> {
        val promise = Promise.promise<Int>()
        launch(vertx.dispatcher()) {
            promise.complete(ProbeBridge.getConnectedProbeCount(vertx))
        }
        return promise.future()
    }
}
