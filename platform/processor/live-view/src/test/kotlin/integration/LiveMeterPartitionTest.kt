/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package integration

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
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
            delay(500)
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
