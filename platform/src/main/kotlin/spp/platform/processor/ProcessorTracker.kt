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
package spp.platform.processor

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.SourcePlatform
import spp.protocol.platform.PlatformAddress
import spp.protocol.status.ActiveProcessor
import spp.protocol.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProcessorTracker : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val activeProcessors: MutableMap<String, ActiveProcessor> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(activeProcessorsAddress) {
            launch(vertx.dispatcher()) {
                it.reply(ArrayList(activeProcessors.values))
            }
        }
        vertx.eventBus().consumer<JsonObject>(connectedProcessorsAddress) {
            launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        PlatformAddress.PROCESSOR_CONNECTED.address
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROCESSOR_CONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { "Establishing connection with processor ${conn.instanceId}" }

            activeProcessors[conn.instanceId] = ActiveProcessor(
                conn.instanceId, System.currentTimeMillis(), meta = conn.meta
            )
            it.reply(true)

            log.info(
                "Processor connected. Latency: {}ms - Active processors: {}",
                latency, activeProcessors.size
            )

            launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROCESSOR_CONNECTED.address).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROCESSOR_DISCONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val connectedAt = Instant.ofEpochMilli(activeProcessors.remove(conn.instanceId)!!.connectedAt)
            log.info("Processor disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

            launch(vertx.dispatcher()) {
                SourcePlatform.discovery.getRecords { true }.await().forEach {
                    if (it.metadata.getString("INSTANCE_ID") == conn.instanceId) {
                        SourcePlatform.discovery.unpublish(it.registration)
                    }
                }

                vertx.sharedData().getLocalCounter(PlatformAddress.PROCESSOR_CONNECTED.address).await()
                    .decrementAndGet().await()
            }
        }
    }

    companion object {
        private const val connectedProcessorsAddress = "get-connected-processors"
        private const val activeProcessorsAddress = "get-active-processors"

        suspend fun getConnectedProcessorCount(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedProcessorsAddress, null).await().body()
        }

        suspend fun getActiveProcessors(vertx: Vertx): List<ActiveProcessor> {
            return vertx.eventBus().request<List<ActiveProcessor>>(activeProcessorsAddress, null).await().body()
        }
    }
}
