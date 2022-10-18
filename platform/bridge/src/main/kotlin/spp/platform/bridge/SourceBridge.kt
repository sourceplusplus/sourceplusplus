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
package spp.platform.bridge

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.bridge.probe.ProbeBridge
import spp.platform.common.service.SourceBridgeService
import spp.platform.storage.SourceStorage
import spp.protocol.platform.PlatformAddress.MARKER_CONNECTED
import spp.protocol.platform.PlatformAddress.PROBE_CONNECTED

class SourceBridge : CoroutineVerticle(), SourceBridgeService {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun getActiveMarkers(): Future<JsonArray> {
        log.trace { "Getting active markers" }
        val promise = Promise.promise<JsonArray>()
        launch(vertx.dispatcher()) {
            val map = SourceStorage.map<String, JsonObject>(BridgeAddress.ACTIVE_MARKERS)
            map.values().onSuccess {
                promise.complete(JsonArray().apply { it.forEach { add(it) } })
            }.onFailure {
                log.error("Failed to get active markers", it)
                promise.fail(it)
            }
        }
        return promise.future()
    }

    override fun getActiveProbes(): Future<JsonArray> {
        log.trace { "Getting active probes" }
        val promise = Promise.promise<JsonArray>()
        launch(vertx.dispatcher()) {
            val map = ProbeBridge.getActiveProbesMap()
            map.values().onSuccess {
                promise.complete(JsonArray().apply { it.forEach { add(it) } })
            }.onFailure {
                log.error("Failed to get active probes", it)
                promise.fail(it)
            }
        }
        return promise.future()
    }

    override fun getConnectedMarkers(): Future<Int> {
        log.trace { "Getting connected markers" }
        val promise = Promise.promise<Int>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.counter(MARKER_CONNECTED).get().await().toInt())
        }
        return promise.future()
    }

    override fun getConnectedProbes(): Future<Int> {
        log.trace { "Getting connected probes" }
        val promise = Promise.promise<Int>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.counter(PROBE_CONNECTED).get().await().toInt())
        }
        return promise.future()
    }

    override fun updateActiveProbeMetadata(id: String, metadata: JsonObject): Future<JsonObject> {
        log.trace { "Updating probe metadata for $id" }
        val promise = Promise.promise<JsonObject>()
        launch(vertx.dispatcher()) {
            val map = ProbeBridge.getActiveProbesMap()
            map.get(id).onSuccess {
                if (it == null) {
                    promise.fail("Probe $id not found")
                } else {
                    val updatedInstanceConnection = it.apply {
                        getJsonObject("meta").mergeIn(metadata)
                    }
                    map.put(id, it).onSuccess {
                        promise.complete(updatedInstanceConnection)
                    }.onFailure {
                        log.error("Failed to update probe metadata for $id", it)
                        promise.fail(it)
                    }
                }
            }.onFailure {
                log.error("Failed to update probe metadata for $id", it)
                promise.fail(it)
            }
        }
        return promise.future()
    }
}
