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

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.SourceServices.Provide.toLiveViewSubscriberAddress
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.LiveViewSubscription
import java.util.*

class SimpleCountLiveMeterTest : LiveInstrumentIntegrationTest() {

    private fun triggerCount() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun testCountIncrement(): Unit = runBlocking {
        setupLineLabels {
            triggerCount()
        }

        val testContext = VertxTestContext()
        val meterId = UUID.randomUUID().toString()
        val liveMeter = LiveMeter(
            "simple-count-meter",
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                SimpleCountLiveMeterTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId,
            meta = mapOf("metric.mode" to "RATE")
            //applyImmediately = true //todo: can't use applyImmediately
        )

        viewService.addLiveViewSubscription(
            LiveViewSubscription(
                entityIds = listOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    SimpleCountLiveMeterTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    SimpleCountLiveMeterTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                liveViewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.toMetricId())
                )
            )
        ).await()
        val consumer = vertx.eventBus().consumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        var totalCount = 0
        consumer.handler {
            val liveViewEvent = Json.decodeValue(it.body().toString(), LiveViewEvent::class.java)
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 100) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).onSuccess {
            //trigger live meter 100 times
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                for (i in 0 until 100) {
                    triggerCount()
                }
            }
        }.onFailure {
            testContext.failNow(it)
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())

        assertEquals(100, totalCount)
    }
}
