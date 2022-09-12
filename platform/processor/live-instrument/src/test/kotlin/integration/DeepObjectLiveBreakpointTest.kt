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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("unused", "UNUSED_VARIABLE")
class DeepObjectLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    class Layer1 {
        val layer2 = Layer2()

        class Layer2 {
            val layer3 = Layer3()

            class Layer3 {
                val layer4 = Layer4()

                class Layer4 {
                    val layer5 = Layer5()

                    class Layer5 {
                        val finalInt = 0
                    }
                }
            }
        }
    }

    private fun deepObject() {
        startEntrySpan("deepObject")
        val deepObject = Layer1()
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `max depth exceeded`() {
        setupLineLabels {
            deepObject()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //layer1
                val layer1Object = topFrame.variables.first { it.name == "deepObject" }
                assertEquals(
                    "integration.DeepObjectLiveBreakpointTest\$Layer1",
                    layer1Object.liveClazz
                )

                //layer2
                val layer2Object = (layer1Object.value as JsonArray).first() as JsonObject
                assertEquals(
                    "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2",
                    layer2Object.getString("liveClazz")
                )

                //layer3
                val layer3Object = layer2Object.getJsonArray("value").first() as JsonObject
                assertEquals(
                    "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2\$Layer3",
                    layer3Object.getString("liveClazz")
                )

                //layer4
                val layer4Object = (layer3Object.getJsonArray("value")).first() as JsonObject
                assertEquals(
                    "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2\$Layer3\$Layer4",
                    layer4Object.getString("liveClazz")
                )

                //layer5
                val layer5Object = layer4Object.getJsonArray("value").first() as JsonObject
                assertEquals(
                    "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2\$Layer3\$Layer4\$Layer5",
                    layer5Object.getString("liveClazz")
                )

                //finalInt
                val finalInt = layer5Object.getJsonArray("value").first() as JsonObject
                assertEquals(
                    "java.lang.Integer",
                    finalInt.getString("liveClazz")
                )

                //max depth exceeded
                val finalIntValue = finalInt.getJsonObject("value")
                assertEquals(
                    "MAX_DEPTH_EXCEEDED",
                    finalIntValue.getString("@skip")
                )
            }

            //test passed
            testContext.completeNow()
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause())
                return@completionHandler
            }

            //add live breakpoint
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        DeepObjectLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    deepObject()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
