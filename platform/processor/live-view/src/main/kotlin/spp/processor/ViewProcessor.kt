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
package spp.processor

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.metrics.DataTable
import org.apache.skywalking.oap.server.core.query.MetricsQueryService
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.core.storage.query.IMetadataQueryDAO
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.FeedbackProcessor
import spp.platform.storage.ExpiringSharedData
import spp.platform.storage.SourceStorage
import spp.processor.live.impl.LiveViewServiceImpl
import spp.processor.live.impl.view.model.ClusterMetrics
import spp.protocol.service.LiveViewService
import spp.protocol.service.SourceServices
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

object ViewProcessor : FeedbackProcessor() {

    val realtimeMetricCache: ExpiringSharedData<String, ClusterMetrics>
        get() = ExpiringSharedData.newBuilder()
            .expireAfterAccess(3, TimeUnit.MINUTES)
            .build("realtimeMetricCache", SourceStorage.storage)

    lateinit var metricsQueryService: MetricsQueryService
    lateinit var metadata: IMetadataQueryDAO
    private val log = LoggerFactory.getLogger(ViewProcessor::class.java)
    val liveViewService = LiveViewServiceImpl()
    private var liveViewRecord: Record? = null

    override fun bootProcessor(moduleManager: ModuleManager) {
        module = moduleManager

        module!!.find(StorageModule.NAME).provider().apply {
            metadata = getService(IMetadataQueryDAO::class.java)
        }
        module!!.find(CoreModule.NAME).provider().apply {
            metricsQueryService = getService(MetricsQueryService::class.java)
        }

        connectToPlatform()
    }

    override fun onConnected(vertx: Vertx) {
        log.debug("Deploying view processor")
        vertx.deployVerticle(ViewProcessor) {
            if (it.succeeded()) {
                processorVerticleId = it.result()
            } else {
                log.error("Failed to deploy view processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun start() {
        log.info("Starting ViewProcessor")
        val module = SimpleModule()
        module.addSerializer(DataTable::class.java, object : JsonSerializer<DataTable>() {
            override fun serialize(value: DataTable, gen: JsonGenerator, provider: SerializerProvider) {
                val data = mutableMapOf<String, Long>()
                value.keys().forEach { data[it] = value.get(it) }
                gen.writeStartObject()
                data.forEach {
                    gen.writeNumberField(it.key, it.value)
                }
                gen.writeEndObject()
            }
        })
        DatabindCodec.mapper().registerModule(module)

        vertx.deployVerticle(liveViewService).await()

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .setAddress(SourceServices.LIVE_VIEW)
            .addInterceptor(developerAuthInterceptor())
            .register(LiveViewService::class.java, liveViewService)
        liveViewRecord = EventBusService.createRecord(
            SourceServices.LIVE_VIEW,
            SourceServices.LIVE_VIEW,
            LiveViewService::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        discovery.publish(liveViewRecord) {
            if (it.succeeded()) {
                log.info("Live view service published")
            } else {
                log.error("Failed to publish live view service", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun stop() {
        log.info("Stopping ViewProcessor")
        discovery.unpublish(liveViewRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live view service unpublished")
            } else {
                log.error("Failed to unpublish live view service", it.cause())
            }
        }.await()
    }
}
