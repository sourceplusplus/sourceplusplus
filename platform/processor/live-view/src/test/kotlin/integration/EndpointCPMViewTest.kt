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
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.apm.toolkit.trace.Tracer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.artifact.metrics.MetricType
import spp.protocol.platform.general.Service
import spp.protocol.platform.general.util.IDManager
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent

class EndpointCPMViewTest : PlatformIntegrationTest() {

    private fun fakeEndpoint() {
        Tracer.createEntrySpan("fakeEndpoint", null)
        Tracer.stopSpan()
    }

    @Test
    fun `endpoint cpm view`(): Unit = runBlocking {
        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(MetricType.Endpoint_RespTime_AVG.asRealtime().metricId),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(MetricType.Endpoint_RespTime_AVG.asRealtime().metricId)
                ),
                location = Service.fromName("spp-test-probe")
            )
        ).await().subscriptionId!!
        log.info("Subscription id: $subscriptionId")

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: $rawMetrics")

            testContext.verify {
                assertEquals(
                    "fakeEndpoint",
                    IDManager.EndpointID.analysisId(rawMetrics.getString("entityId")).endpointName
                )
            }
            testContext.completeNow()
        }

        fakeEndpoint()
        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
