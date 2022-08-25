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

import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType

class SimpleCountLiveMeterTest : LiveInstrumentIntegrationTest() {

    private fun triggerCount() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun testCountIncrement() {
        setupLineLabels {
            triggerCount()
        }

        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveMeter(
                "simple-count-meter2",
                MeterType.COUNT,
                MetricValue(MetricValueType.NUMBER, "1"),
                location = LiveSourceLocation(
                    SimpleCountLiveMeterTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    //"spp-test-probe" //todo: impl this so applyImmediately can be used
                ),
                meta = mapOf("metric.mode" to "RATE")
                //applyImmediately = true //todo: can't use applyImmediately
            )
        ).onSuccess {
            //trigger live meter 10 times
            vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                for (i in 0 until 100) {
                    triggerCount()
                    Thread.sleep(250)
                }
            }
        }.onFailure {
            testContext.failNow(it)
        }

        errorOnTimeout(testContext, 300)
    }
}
