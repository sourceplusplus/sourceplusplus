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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.*
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.RulePartition
import spp.protocol.view.rule.ViewRule

class LiveMeterPartitionTest : LiveInstrumentIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun doTest(index: Int) {
        var i = index % 2 == 0
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun `live meter partitions`(): Unit = runBlocking {
        setupLineLabels {
            doTest(-1)
        }

        val liveMeter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.NUMBER, "1"),
            meterPartitions = listOf(
                MeterPartition(
                    valueType = MeterValueType.VALUE_EXPRESSION,
                    value = "localVariables[i]"
                )
            ),
            meta = mapOf("metric.mode" to "RATE"),
            location = LiveSourceLocation(
                LiveMeterPartitionTest::class.java.name,
                getLineNumber("done"),
                "spp-test-probe"
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        viewService.saveRule(
            ViewRule(
                liveMeter.id!!,
                "(the_count.sum(['service']).downsampling(SUM)).service(['service'], Layer.GENERAL)",
                listOf(
                    RulePartition(
                        "the_count",
                        "${liveMeter.id}_\$partition\$"
                    )
                ),
                listOf(liveMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf("${liveMeter.id}_true", "${liveMeter.id}_false"),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf("${liveMeter.id}_true", "${liveMeter.id}_false")
                )
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonArray(liveViewEvent.metricsData)
            log.info("Raw metrics: $rawMetrics")

            val trueCount = (rawMetrics.find {
                (it as JsonObject).getString("metric_type") == "${liveMeter.id}_true"
            } as JsonObject).getInteger("value")
            println(trueCount)
            val falseCount = (rawMetrics.find {
                (it as JsonObject).getString("metric_type") == "${liveMeter.id}_false"
            } as JsonObject).getInteger("value")
            println(falseCount)
            if (trueCount + falseCount == 10) {
                testContext.verify {
                    assertEquals(5, trueCount)
                    assertEquals(5, falseCount)
                    assertEquals(10, trueCount + falseCount)
                }
                testContext.completeNow()
            }
        }

        assertNotNull(instrumentService.addLiveInstrument(liveMeter).await())

        repeat(10) {
            doTest(it)
        }

        errorOnTimeout(testContext, 30)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(liveMeter.id!!).await())
    }
}
