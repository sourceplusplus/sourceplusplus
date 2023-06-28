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
package integration

import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.oap.server.core.analysis.IDManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.probe.ProbeConfiguration
import spp.protocol.artifact.metrics.MetricType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent

@Isolated
class VCSLiveViewIT : PlatformIntegrationTest() {

    @Test
    fun `specific versioned realtime instance_jvm_cpu`(): Unit = runBlocking {
        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(MetricType.INSTANCE_JVM_CPU.asRealtime().metricId),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(MetricType.INSTANCE_JVM_CPU.asRealtime().metricId)
                ),
                artifactLocation = LiveSourceLocation(
                    "",
                    service = Service.fromName("spp-test-probe"),
                    commitId = "test1"
                )
            )
        ).await().subscriptionId!!

        val probeId = ProbeConfiguration.PROBE_ID
        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("git_commit", "test1")
            )
        ).await()
        delay(2000)
        var testContext = VertxTestContext()
        verifyHit(testContext, subscriptionId, IDManager.ServiceID.buildId("spp-test-probe|test1", true))
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("git_commit", "test2")
            )
        ).await()
        delay(2000)
        testContext = VertxTestContext()
        verifyHit(testContext, subscriptionId, IDManager.ServiceID.buildId("spp-test-probe|test2", true), false)
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        //clean up
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    @Test
    fun `versioned realtime instance_jvm_cpu`(): Unit = runBlocking {
        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(MetricType.INSTANCE_JVM_CPU.asRealtime().metricId),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(MetricType.INSTANCE_JVM_CPU.asRealtime().metricId)
                ),
                artifactLocation = LiveSourceLocation(
                    "",
                    service = Service.fromName("spp-test-probe")
                )
            )
        ).await().subscriptionId!!

        val probeId = ProbeConfiguration.PROBE_ID
        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("git_commit", "test1")
            )
        ).await()
        delay(2000)
        var testContext = VertxTestContext()
        verifyHit(testContext, subscriptionId, IDManager.ServiceID.buildId("spp-test-probe|test1", true))
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("git_commit", "test2")
            )
        ).await()
        delay(2000)
        testContext = VertxTestContext()
        verifyHit(testContext, subscriptionId, IDManager.ServiceID.buildId("spp-test-probe|test2", true))
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        //clean up
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }

    private suspend fun verifyHit(
        testContext: VertxTestContext,
        subscriptionId: String,
        verifyServiceId: String,
        errorOnTimeout: Boolean = true
    ) {
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: $rawMetrics")

            testContext.verify {
                assertEquals(verifyServiceId, rawMetrics.getString("serviceId"))
            }
            testContext.completeNow()
        }
        if (errorOnTimeout) {
            errorOnTimeout(testContext)
        } else {
            successOnTimeout(testContext)
        }
        consumer.unregister().await()
    }
}
