/*
 * Source++, the continuous feedback platform for developers.
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
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.*

class LiveMeterGaugeTest : LiveInstrumentIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private fun triggerGauge() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun testGauge(): Unit = runBlocking {
        setupLineLabels {
            triggerGauge()
        }

        val meterId = "test-gauge"
        log.info("Using meter id: {}", meterId)

        val supplier: () -> Double = { System.currentTimeMillis().toDouble() }
        val encodedSupplier = Base64.getEncoder().encodeToString(ByteArrayOutputStream().run {
            ObjectOutputStream(this).apply { writeObject(supplier) }
            toByteArray()
        })

        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.NUMBER_SUPPLIER, encodedSupplier),
            location = LiveSourceLocation(
                LiveMeterGaugeTest::class.qualifiedName!!,
                getLineNumber("done"),
                //"spp-test-probe" //todo: impl this so applyImmediately can be used
            ),
            id = meterId,
            //applyImmediately = true //todo: can't use applyImmediately
        )

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.toMetricId()),
                artifactQualifiedName = ArtifactQualifiedName(
                    LiveMeterGaugeTest::class.qualifiedName!!,
                    type = ArtifactType.EXPRESSION
                ),
                artifactLocation = LiveSourceLocation(
                    LiveMeterGaugeTest::class.qualifiedName!!,
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
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.toMetricId(), meta.getString("metricsName"))

                //check within a second
                val suppliedTime = rawMetrics.getLong("value")
                log.info("Supplied time: {}", suppliedTime)

                assertTrue(suppliedTime >= System.currentTimeMillis() - 1000)
                assertTrue(suppliedTime <= System.currentTimeMillis())
            }
            testContext.completeNow()
        }

        instrumentService.addLiveInstrument(liveMeter).onSuccess {
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                triggerGauge()
            }
        }.onFailure {
            testContext.failNow(it)
        }

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(instrumentService.removeLiveInstrument(meterId).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
