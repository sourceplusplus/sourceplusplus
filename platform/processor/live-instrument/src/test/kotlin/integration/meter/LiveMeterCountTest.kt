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
import io.vertx.core.Handler
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.MeterSumRule

@Isolated
class LiveMeterCountTest : LiveInstrumentIntegrationTest() {

    private fun count1() {
        addLineLabel("count1") { Throwable().stackTrace[0].lineNumber }
    }

    private fun count2() {
        addLineLabel("count2") { Throwable().stackTrace[0].lineNumber }
    }

    private fun count3() {
        addLineLabel("count3") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun testCountIncrement(): Unit = runBlocking {
        setupLineLabels {
            count1()
        }

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.java.name,
                getLineNumber("count1"),
                "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        val viewRule = viewService.saveRule(MeterSumRule(liveMeter)).await()
        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!)),
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        var totalCount = 0
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 100) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        //trigger live meter 100 times
        repeat((0 until 100).count()) {
            count1()
        }

        errorOnTimeout(testContext)
        log.info("Total count: {}", totalCount)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(viewRule.name).await())

        assertEquals(100, totalCount)
    }

    @Test
    fun testDoubleCountIncrement(): Unit = runBlocking {
        setupLineLabels {
            count2()
        }

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "2"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.java.name,
                getLineNumber("count2"),
                "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        val viewRule = viewService.saveRule(MeterSumRule(liveMeter)).await()
        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!))
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        var totalCount = 0
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 200) {
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        //trigger live meter 100 times
        repeat((0 until 100).count()) {
            count2()
        }

        errorOnTimeout(testContext)
        log.info("Total count: {}", totalCount)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(viewRule.name).await())

        assertEquals(200, totalCount)
    }

    @Test
    fun `one method two counts`(): Unit = runBlocking {
        setupLineLabels {
            count3()
        }

        val liveMeter1 = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.java.name,
                getLineNumber("count3"),
                "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        val viewRule1 = viewService.saveRule(MeterSumRule(liveMeter1)).await()
        val subscriptionId1 = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter1.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter1.id!!))
            )
        ).await().subscriptionId!!

        val liveMeter2 = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "100"),
            location = LiveSourceLocation(
                LiveMeterCountTest::class.java.name,
                getLineNumber("count3"),
                "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            meta = mapOf("metric.mode" to "RATE"),
            applyImmediately = true
        )

        val viewRule2 = viewService.saveRule(MeterSumRule(liveMeter2)).await()
        val subscriptionId2 = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter2.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter2.id!!))
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        var totalCount = 0
        val handler = Handler<Message<JsonObject>> {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertTrue(
                    liveMeter1.id!! == meta.getString("metricsName") ||
                            liveMeter2.id!! == meta.getString("metricsName")
                )

                totalCount += rawMetrics.getInteger("value")
                if (totalCount >= 202) {
                    testContext.completeNow()
                }
            }
        }
        val consumer1 = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscriberAddress(subscriptionId1))
        consumer1.handler(handler)
        val consumer2 = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscriberAddress(subscriptionId2))
        consumer2.handler(handler)

        instrumentService.addLiveMeter(liveMeter1).await()
        instrumentService.addLiveMeter(liveMeter2).await()
        count3()
        count3()

        errorOnTimeout(testContext)
        log.info("Total count: {}", totalCount)

        //clean up
        consumer1.unregister()
        consumer2.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter1.id!!).await())
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter2.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId1).await())
        assertNotNull(viewService.removeLiveView(subscriptionId2).await())
        assertNotNull(viewService.deleteRule(viewRule1.name).await())
        assertNotNull(viewService.deleteRule(viewRule2.name).await())

        assertEquals(202, totalCount)
    }
}
