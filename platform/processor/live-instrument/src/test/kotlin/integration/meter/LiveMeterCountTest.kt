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
package integration.meter

import integration.LiveInstrumentIntegrationTest
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent

@Disabled //todo: this test is flaky
class LiveMeterCountTest : LiveInstrumentIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private fun triggerCount() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun testCountIncrement(): Unit = runBlocking {
        setupLineLabels {
            triggerCount()
        }

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                "spp-test-probe"
            ),
            id = testNameAsInstrumentId,
            applyImmediately = true
        )

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.toMetricId())
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().consumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        var totalCount = 0
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 100) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        //trigger live meter 100 times
        repeat((0 until 100).count()) {
            triggerCount()
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(testNameAsInstrumentId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())

        assertEquals(100, totalCount)
    }

    @Test
    fun testDoubleCountIncrement(): Unit = runBlocking {
        setupLineLabels {
            triggerCount()
        }

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "2"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                "spp-test-probe"
            ),
            id = testNameAsInstrumentId,
            applyImmediately = true
        )

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.toMetricId())
                )
            )
        ).await().subscriptionId!!
        val consumer = vertx.eventBus().consumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        var totalCount = 0
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 200) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        //trigger live meter 100 times
        repeat((0 until 100).count()) {
            triggerCount()
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(testNameAsInstrumentId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())

        assertEquals(200, totalCount)
    }

    @Test
    fun `one method two counts`(): Unit = runBlocking {
        setupLineLabels {
            triggerCount()
        }

        val liveMeter1 = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = "$testNameAsInstrumentId-1",
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId1 = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter1.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter1.toMetricId())
                )
            )
        ).await().subscriptionId!!

        val liveMeter2 = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "100"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = "$testNameAsInstrumentId-2",
            meta = mapOf("metric.mode" to "RATE")
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId2 = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter2.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter2.toMetricId())
                )
            )
        ).await().subscriptionId!!

        val consumer = vertx.eventBus().consumer<JsonObject>(
            toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        var totalCount = 0
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertTrue(
                    liveMeter1.toMetricId() == meta.getString("metricsName") ||
                            liveMeter2.toMetricId() == meta.getString("metricsName")
                )

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 202) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveMeter(liveMeter1).onSuccess {
            val metricId = it.toMetricId()
            println(metricId)
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                triggerCount()
            }
        }.onFailure {
            testContext.failNow(it)
        }
        instrumentService.addLiveMeter(liveMeter2).onSuccess {
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                triggerCount()
            }
        }.onFailure {
            testContext.failNow(it)
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument("$testNameAsInstrumentId-1").await())
        assertNotNull(instrumentService.removeLiveInstrument("$testNameAsInstrumentId-2").await())
        assertNotNull(viewService.removeLiveView(subscriptionId1).await())
        assertNotNull(viewService.removeLiveView(subscriptionId2).await())

        assertEquals(202, totalCount)
    }
}
