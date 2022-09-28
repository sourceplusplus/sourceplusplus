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
package integration

import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.SourceServices
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent

class RealtimeLiveViewTest : PlatformIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Test
    fun `realtime instance_jvm_cpu`(): Unit = runBlocking {
        viewService.clearLiveViews().await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf("instance_jvm_cpu_realtime"),
                artifactQualifiedName = ArtifactQualifiedName( //todo: optional artifact
                    "unneeded",
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    "unneeded", //todo: optional location
                    -1
                ),
                liveViewConfig = LiveViewConfig(
                    "test",
                    listOf("instance_jvm_cpu_realtime")
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().consumer<JsonObject>(
            SourceServices.Subscribe.toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        var totalCount = 0
        val countSet = mutableSetOf<String>()
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            if (rawMetrics.getString("serviceId") != "c3BwLXRlc3QtcHJvYmU=.1") {
                return@handler
            }
            log.info("Received metrics: $rawMetrics")

            testContext.verify {
                val timeBucket = rawMetrics.getString("timeBucket")
                assertNotNull(timeBucket)
                val count = rawMetrics.getInteger("count")
                assertNotNull(count)

                //should never receive duplicate count for the same timeBucket
                assertTrue(countSet.add("$timeBucket-$count"))

                if (totalCount++ >= 30) {
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
