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
import spp.processor.live.impl.LiveViewProcessorImpl
import spp.protocol.SourceServices
import spp.protocol.service.LiveViewService
import kotlin.system.exitProcess

object ViewProcessor : FeedbackProcessor() {

    lateinit var metricsQueryService: MetricsQueryService
    lateinit var metadata: IMetadataQueryDAO
    private val log = LoggerFactory.getLogger(ViewProcessor::class.java)
    val liveViewProcessor = LiveViewProcessorImpl()
    private var liveViewRecord: Record? = null

    override fun bootProcessor(moduleManager: ModuleManager) {
        module = moduleManager

        module!!.find(StorageModule.NAME).provider().apply {
            metadata = getService(IMetadataQueryDAO::class.java)
        }
        module!!.find(CoreModule.NAME).provider().apply {
            metricsQueryService = getService(MetricsQueryService::class.java)
        }

        log.info("ViewProcessor initialized")
        connectToPlatform()
    }

    override fun onConnected(vertx: Vertx) {
        log.info("Deploying source processor")
        vertx.deployVerticle(ViewProcessor) {
            if (it.succeeded()) {
                processorVerticleId = it.result()
            } else {
                log.error("Failed to deploy source processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun start() {
        log.info("Starting ViewProcessorVerticle")
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

        vertx.deployVerticle(liveViewProcessor).await()

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .setAddress(SourceServices.Utilize.LIVE_VIEW)
            .addInterceptor { developerAuthInterceptor(it) }
            .register(LiveViewService::class.java, liveViewProcessor)
        liveViewRecord = EventBusService.createRecord(
            SourceServices.Utilize.LIVE_VIEW,
            SourceServices.Utilize.LIVE_VIEW,
            LiveViewService::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        discovery.publish(liveViewRecord) {
            if (it.succeeded()) {
                log.info("Live view processor published")
            } else {
                log.error("Failed to publish live view processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun stop() {
        log.info("Stopping ViewProcessorVerticle")
        discovery.unpublish(liveViewRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live view processor unpublished")
            } else {
                log.error("Failed to unpublish live view processor", it.cause())
            }
        }.await()
    }
}
