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

import io.vertx.core.CompositeFuture
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import mu.KotlinLogging
import org.apache.skywalking.oap.server.library.module.ModuleManager
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.FeedbackProcessor
import spp.platform.storage.SourceStorage
import spp.processor.live.impl.LiveInsightServiceImpl
import spp.processor.live.impl.insight.types.function.duration.FunctionDurationModerator
import spp.processor.live.impl.moderate.InsightModerator
import spp.processor.live.impl.moderate.WorkspaceInsightQueue
import spp.protocol.service.*
import kotlin.system.exitProcess

object InsightProcessor : FeedbackProcessor() {

    private val log = KotlinLogging.logger {}
    lateinit var viewService: LiveViewService
    lateinit var instrumentService: LiveInstrumentService
    private var liveInsightRecord: Record? = null
    val moderators = mutableListOf<InsightModerator>()
    val workspaceQueue = WorkspaceInsightQueue()

    override fun bootProcessor(moduleManager: ModuleManager) {
        module = moduleManager

        //add moderators
        moderators.add(FunctionDurationModerator())

        log.info("InsightProcessor initialized")
        connectToPlatform()
    }

    override fun onConnected(vertx: Vertx) {
        log.info("Deploying insight processor")
        vertx.deployVerticle(InsightProcessor) {
            if (it.succeeded()) {
                processorVerticleId = it.result()
            } else {
                log.error("Failed to deploy insight processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun start() {
        log.info("Starting InsightProcessor")

        //todo: create `insight-automation` developer account
        val systemAccessToken = SourceStorage.get<String>("system_access_token")!!
        val authToken = LiveManagementService.createProxy(vertx).getAuthToken(systemAccessToken).await()
        viewService = LiveViewService.createProxy(vertx, authToken)
        instrumentService = LiveInstrumentService.createProxy(vertx, authToken)

        //deploy moderators
        val workerOptions = DeploymentOptions().setWorker(true)
        CompositeFuture.all(moderators.map { vertx.deployVerticle(it, workerOptions) }).await()

        vertx.deployVerticle(workspaceQueue).await()

        val liveInsightService = LiveInsightServiceImpl()
        vertx.deployVerticle(liveInsightService).await()

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .setAddress(SourceServices.LIVE_INSIGHT)
            .addInterceptor(developerAuthInterceptor())
            .register(LiveInsightService::class.java, liveInsightService)
        liveInsightRecord = EventBusService.createRecord(
            SourceServices.LIVE_INSIGHT,
            SourceServices.LIVE_INSIGHT,
            LiveInsightService::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        discovery.publish(liveInsightRecord) {
            if (it.succeeded()) {
                log.info("Live insight service published")
            } else {
                log.error("Failed to publish live insight service", it.cause())
                exitProcess(-1)
            }
        }

//        val insightConfig = ClusterConnection.config.getJsonObject("live-insight")
//        val importDir = insightConfig.getString("graph_listing_directory")
//        ClusterConnection.router.route("/insight/data/*").handler(
//            StaticHandler.create(FileSystemAccess.ROOT, importDir)
//                .setDirectoryListing(insightConfig.getString("graph_listing_enabled").toBooleanStrict())
//        )
    }

    override suspend fun stop() {
        log.info("Stopping InsightProcessor")
        discovery.unpublish(liveInsightRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live insight service unpublished")
            } else {
                log.error("Failed to unpublish live insight service", it.cause())
            }
        }.await()
    }
}
