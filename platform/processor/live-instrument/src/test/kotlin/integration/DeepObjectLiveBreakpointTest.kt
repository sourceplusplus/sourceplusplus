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

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.marshall.ProtocolMarshaller
import java.util.concurrent.TimeUnit

@Suppress("unused", "UNUSED_VARIABLE")
class DeepObjectLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    class Layer1 {
        val layer2 = Layer2()

        class Layer2 {
            val layer3 = Layer3()

            class Layer3 {
                val layer4 = Layer4()

                class Layer4 {
                    val finalInt = 0
                }
            }
        }
    }

    private fun deepObject() {
        val activeSpan = startEntrySpan("deepObject")
        val deepObject = Layer1()
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan(activeSpan)
    }

    @Test
    fun `max depth exceeded`() {
        setupLineLabels {
            deepObject()
        }

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                //verify live breakpoint data
                val bpHit = ProtocolMarshaller.deserializeLiveBreakpointHit(JsonObject(event.data))
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
                    val layer2Object = (layer1Object.value as List<*>)[0] as Map<String, *>
                    assertEquals(
                        "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2",
                        layer2Object["liveClazz"]
                    )

                    //layer3
                    val layer3Object = (layer2Object["value"] as List<*>)[0] as Map<String, *>
                    assertEquals(
                        "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2\$Layer3",
                        layer3Object["liveClazz"]
                    )

                    //max depth exceeded
                    val layer4Object = layer3Object["value"] as Map<String, *>
                    assertEquals(
                        "MAX_DEPTH_EXCEEDED",
                        layer4Object["@skip"]
                    )
                    assertEquals(
                        "integration.DeepObjectLiveBreakpointTest\$Layer1\$Layer2\$Layer3",
                        layer4Object["@class"]
                    )
                }

                //test passed
                testContext.completeNow()
            }
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

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
