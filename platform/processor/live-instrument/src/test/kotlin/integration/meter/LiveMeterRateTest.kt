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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.platform.general.Service
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.ViewRule

@Isolated
class LiveMeterRateTest : LiveInstrumentIntegrationTest() {

    private fun triggerRate() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        viewService.clearLiveViews().await()
    }

    @Test
    fun `60 calls per minute rate`(): Unit = runBlocking {
        assumeTrue("true" == System.getProperty("test.includeSlow"))
        setupLineLabels {
            triggerRate()
        }

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            location = LiveSourceLocation(
                LiveMeterRateTest::class.java.name,
                getLineNumber("done"),
                Service.fromName("spp-test-probe")
            ),
            id = testNameAsUniqueInstrumentId,
            meta = mapOf("metric.mode" to "RATE"),
            applyImmediately = true
        )

        viewService.saveRuleIfAbsent(
            ViewRule(
                name = liveMeter.id!!,
                exp = buildString {
                    append("(")
                    append(liveMeter.id!!)
                    append(".sum(['service', 'instance'])")
                    append(".downsampling(SUM)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                },
                meterIds = listOf(liveMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf(liveMeter.id!!)
                ),
                service = Service.fromName("spp-test-probe")
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        var rate = 0
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            log.info("Received metrics: {}", rawMetrics)

            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                rate = rawMetrics.getInteger("value")
                if (rate >= 50) { //allow for some variance (GH actions are sporadic)
                    testContext.completeNow()
                }
            }
        }

        instrumentService.addLiveInstrument(liveMeter).await()

        vertx.executeBlocking<Void> {
            runBlocking {
                //trigger live meter 100 times once per second
                repeat((0 until 100).count()) {
                    triggerRate()
                    delay(1000)
                    if (testContext.completed()) return@runBlocking
                }
            }
            it.complete()
        }

        errorOnTimeout(testContext, 150)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())

        assertTrue(rate >= 50, rate.toString()) //allow for some variance (GH actions are sporadic)
    }
}
