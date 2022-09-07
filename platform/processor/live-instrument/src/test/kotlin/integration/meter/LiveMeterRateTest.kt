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
package integration.meter

import integration.LiveInstrumentIntegrationTest
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
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

class LiveMeterRateTest : LiveInstrumentIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private fun triggerRate() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        viewService.clearLiveViewSubscriptions().await()
    }

    @Test
    fun `60 calls per minute rate`(): Unit = runBlocking {
        setupLineLabels {
            triggerRate()
        }

        val meterId = UUID.randomUUID().toString()
        log.info("Using meter id: {}", meterId)

        val liveMeter = LiveMeter(
            "simple-count-meter",
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterRateTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId,
            meta = mapOf("metric.mode" to "RATE")
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId = viewService.addLiveViewSubscription(
            LiveViewSubscription(
                entityIds = listOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterRateTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterRateTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                liveViewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.toMetricId())
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().consumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        var rate = 0
        consumer.handler {
            val liveViewEvent = Json.decodeValue(it.body().toString(), LiveViewEvent::class.java)
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                rate = rawMetrics.getInteger("value")
                if (rate >= 60) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).onSuccess {
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                vertx.executeBlocking<Void> {
                    runBlocking {
                        //trigger live meter 100 times once per second
                        repeat((0 until 100).count()) {
                            triggerRate()
                            delay(1000)
                        }
                    }
                    it.complete()
                }
            }
        }.onFailure {
            testContext.failNow(it)
        }

        errorOnTimeout(testContext, 120)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveViewSubscription(subscriptionId).await())

        assertEquals(60, rate)
    }
}
