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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.protocol.artifact.metrics.MetricType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import java.util.concurrent.atomic.AtomicInteger

@Isolated
class RealtimeLiveViewTest : PlatformIntegrationTest() {

    @Test
    fun `realtime instance_jvm_cpu`(): Unit = runBlocking {
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

        val testContext = VertxTestContext()
        val totalCount = AtomicInteger(0)
        val countSet = mutableSetOf<String>()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: $rawMetrics")

            testContext.verify {
                val timeBucket = rawMetrics.getString("timeBucket")
                assertNotNull(timeBucket)
                val count = rawMetrics.getInteger("count")
                assertNotNull(count)

                //should never receive duplicate count for the same timeBucket
                assertTrue(countSet.add("$timeBucket-$count"))

                if (totalCount.incrementAndGet() >= 30) {
                    testContext.completeNow()
                }
            }
        }

        errorOnTimeout(testContext, 45)

        //clean up
        consumer.unregister()
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
