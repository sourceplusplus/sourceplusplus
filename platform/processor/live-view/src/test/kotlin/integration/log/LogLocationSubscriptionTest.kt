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
package integration.log

import integration.LiveInstrumentIntegrationTest
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.protocol.artifact.log.Log
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent

@Isolated //todo: improve robustness
class LogLocationSubscriptionTest : LiveInstrumentIntegrationTest() {

    private fun triggerLog() {
        addLineLabel("done1") { Throwable().stackTrace[0].lineNumber }
        addLineLabel("done2") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun `test live log location subscription`(): Unit = runBlocking {
        setupLineLabels {
            triggerLog()
        }

        val liveLog1 = LiveLog(
            "test log",
            emptyList(),
            LiveSourceLocation(
                LogLocationSubscriptionTest::class.java.name,
                getLineNumber("done1"),
                Service.fromName("spp-test-probe")
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )
        val liveLog2 = LiveLog(
            "test log",
            emptyList(),
            LiveSourceLocation(
                LogLocationSubscriptionTest::class.java.name,
                getLineNumber("done2"),
                Service.fromName("spp-test-probe")
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveLog1.logFormat),
                viewConfig = LiveViewConfig("test", listOf("endpoint_logs")),
                location = LiveSourceLocation(
                    LogLocationSubscriptionTest::class.java.name,
                    getLineNumber("done2"),
                    Service.fromName("spp-test-probe")
                )
            )
        ).await().subscriptionId!!
        log.info("Using subscription id: {}", subscriptionId)

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawLog = Log(JsonObject(liveViewEvent.metricsData).getJsonObject("log"))
            log.info("Received log: {}", rawLog)

            testContext.verify {
                assertEquals("test log", rawLog.content)
                assertEquals("Live", rawLog.level)
                assertEquals(getLineNumber("done2"), rawLog.location?.line)
            }
            testContext.completeNow()
        }.completionHandler().await()

        instrumentService.addLiveInstrument(liveLog1).await()
        instrumentService.addLiveInstrument(liveLog2).await()
        triggerLog()
        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
