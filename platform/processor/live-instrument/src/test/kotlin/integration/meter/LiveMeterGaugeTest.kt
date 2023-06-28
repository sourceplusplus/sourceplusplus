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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.*
import java.util.function.Supplier

class LiveMeterGaugeTest : LiveInstrumentIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun triggerGauge() {
        val str = "hello"
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun `test number supplier gauge`(): Unit = runBlocking {
        setupLineLabels {
            triggerGauge()
        }

        val supplier = @Suppress("SerialVersionUIDInSerializableClass") object : Supplier<Double>, Serializable {
            override fun get(): Double = System.currentTimeMillis().toDouble()
        }
        val encodedSupplier = Base64.getEncoder().encodeToString(ByteArrayOutputStream().run {
            ObjectOutputStream(this).apply { writeObject(supplier) }
            toByteArray()
        })

        val instrumentId = testNameAsUniqueInstrumentId
        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.NUMBER_SUPPLIER, encodedSupplier),
            location = LiveSourceLocation(
                LiveMeterGaugeTest::class.java.name,
                getLineNumber("done"),
                Service.fromName("spp-test-probe")
            ),
            id = instrumentId,
            applyImmediately = true,
            hitLimit = 1
        )

        val viewRule = viewService.saveRule(
            ViewRule(
                name = liveMeter.id!!,
                exp = buildString {
                    append("(")
                    append(liveMeter.id!!)
                    append(".downsampling(LATEST)")
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
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            log.info("Received live view event: ${it.body()}")
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                val suppliedTime = rawMetrics.getLong("value")
                log.info("Supplied time: {}", suppliedTime)

                val now = System.currentTimeMillis()
                log.info("Now: {}", now)

                //check within 2 seconds
                assertTrue(suppliedTime >= now - 2000)
                assertTrue(suppliedTime <= now)
            }
            testContext.completeNow()
        }.completionHandler().await()

        instrumentService.addLiveInstrument(liveMeter).await()

        triggerGauge()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(viewRule.name).await())
    }

    @Test
    fun `test value expression gauge`(): Unit = runBlocking {
        setupLineLabels {
            triggerGauge()
        }

        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.VALUE_EXPRESSION, "localVariables[str]"),
            location = LiveSourceLocation(
                LiveMeterGaugeTest::class.java.name,
                getLineNumber("done"),
                Service.fromName("spp-test-probe")
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true,
            hitLimit = 1
        )

        val viewRule = viewService.saveRule(
            ViewRule(
                name = liveMeter.id!!,
                exp = buildString {
                    append("(")
                    append(liveMeter.id)
                    append(".downsampling(LATEST)")
                    append(")")
                    append(".instance(['service'], ['instance'], Layer.GENERAL)")
                },
                meterIds = listOf(liveMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!)),
                service = Service.fromName("spp-test-probe")
            )
        ).await().subscriptionId!!

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveViewSubscription(subscriptionId))
        consumer.handler {
            log.info("Received live view event: ${it.body()}")
            val liveViewEvent = LiveViewEvent(it.body())
            val rawMetrics = JsonObject(liveViewEvent.metricsData)
            testContext.verify {
                val meta = rawMetrics.getJsonObject("meta")
                assertEquals(liveMeter.id!!, meta.getString("metricsName"))

                assertEquals("hello", rawMetrics.getString("value"))
            }
            testContext.completeNow()
        }.completionHandler().await()

        instrumentService.addLiveInstrument(liveMeter).await()

        triggerGauge()

        errorOnTimeout(testContext)

        //clean up
        consumer.unregister()
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(viewRule.name).await())
    }
}
