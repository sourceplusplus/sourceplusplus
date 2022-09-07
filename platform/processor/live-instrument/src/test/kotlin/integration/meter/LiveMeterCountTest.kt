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
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.*
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

        val meterId = UUID.randomUUID().toString()
        log.info("Using meter id: {}", meterId)

        val liveMeter = LiveMeter(
            "simple-count-meter",
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId,
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId = viewService.addLiveViewSubscription(
            LiveViewSubscription(
                entityIds = listOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
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
        var totalCount = 0
        consumer.handler {
            val liveViewEvent = Json.decodeValue(it.body().toString(), LiveViewEvent::class.java)
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

        instrumentService.addLiveInstrument(liveMeter).onSuccess {
            //trigger live meter 100 times
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                repeat((0 until 100).count()) {
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
        assertNotNull(viewService.removeLiveViewSubscription(subscriptionId).await())

        assertEquals(100, totalCount)
    }

    @Test
    fun testDoubleCountIncrement(): Unit = runBlocking {
        setupLineLabels {
            triggerCount()
        }

        val meterId = UUID.randomUUID().toString()
        log.info("Using meter id: {}", meterId)

        val liveMeter = LiveMeter(
            "simple-double-count-meter",
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "2"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId,
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId = viewService.addLiveViewSubscription(
            LiveViewSubscription(
                entityIds = listOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
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
        var totalCount = 0
        consumer.handler {
            val liveViewEvent = Json.decodeValue(it.body().toString(), LiveViewEvent::class.java)
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

        instrumentService.addLiveInstrument(liveMeter).onSuccess {
            //trigger live meter 100 times
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                repeat((0 until 100).count()) {
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
        assertNotNull(viewService.removeLiveViewSubscription(subscriptionId).await())

        assertEquals(200, totalCount)
    }

    @Test
    fun `one method two counts`(): Unit = runBlocking {
        setupLineLabels {
            triggerCount()
        }

        val meterId1 = UUID.randomUUID().toString()
        log.info("Using meter id: {}", meterId1)

        val liveMeter1 = LiveMeter(
            "count1-meter",
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId1,
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId1 = viewService.addLiveViewSubscription(
            LiveViewSubscription(
                entityIds = listOf(liveMeter1.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                liveViewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter1.toMetricId())
                )
            )
        ).await().subscriptionId!!

        val meterId2 = UUID.randomUUID().toString()
        log.info("Using meter id: {}", meterId2)

        val liveMeter2 = LiveMeter(
            "count2-meter",
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "100"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId2,
            meta = mapOf("metric.mode" to "RATE")
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId2 = viewService.addLiveViewSubscription(
            LiveViewSubscription(
                entityIds = listOf(liveMeter2.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterCountTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterCountTest::class.qualifiedName!!,
                    getLineNumber("done")
                ),
                liveViewConfig = LiveViewConfig(
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
            val liveViewEvent = Json.decodeValue(it.body().toString(), LiveViewEvent::class.java)
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

        instrumentService.addLiveInstrument(liveMeter1).onSuccess {
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                triggerCount()
            }
        }.onFailure {
            testContext.failNow(it)
        }
        instrumentService.addLiveInstrument(liveMeter2).onSuccess {
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                triggerCount()
            }
        }.onFailure {
            testContext.failNow(it)
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId1).await())
        assertNotNull(instrumentService.removeLiveInstrument(meterId2).await())
        assertNotNull(viewService.removeLiveViewSubscription(subscriptionId1).await())
        assertNotNull(viewService.removeLiveViewSubscription(subscriptionId2).await())

        assertEquals(202, totalCount)
    }
}
