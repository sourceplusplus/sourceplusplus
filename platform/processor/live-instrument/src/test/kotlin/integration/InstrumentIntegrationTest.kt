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
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.marshall.ProtocolMarshaller
import spp.protocol.marshall.ProtocolMarshaller.deserializeLiveInstrumentRemoved
import java.util.*
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class InstrumentIntegrationTest : PlatformIntegrationTest() {

    companion object {
        private val log = LoggerFactory.getLogger(InstrumentIntegrationTest::class.java)
    }

    @Test
    fun verifyLiveVariables() {
        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false
        val instrumentId = UUID.randomUUID().toString()

        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_ADDED -> {
                    log.info("Got added")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotAdded = true
                }
                LiveInstrumentEventType.BREAKPOINT_APPLIED -> {
                    log.info("Got applied")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotApplied = true
                }
                LiveInstrumentEventType.BREAKPOINT_REMOVED -> {
                    log.info("Got removed")
                    testContext.verify {
                        val bpRemoved = deserializeLiveInstrumentRemoved(JsonObject(liveEvent.data))
                        assertEquals(instrumentId, bpRemoved.liveInstrument.id)
                    }
                    gotRemoved = true
                }
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("breakpointId"))
                    }
                    gotHit = true

                    val bpHit = ProtocolMarshaller.deserializeLiveBreakpointHit(JsonObject(liveEvent.data))
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(12, topFrame.variables.size)

                        //byte
                        assertEquals(-2, topFrame.variables.find { it.name == "b" }!!.value)
                        assertEquals(
                            "java.lang.Byte",
                            topFrame.variables.find { it.name == "b" }!!.liveClazz
                        )

                        //char
                        assertEquals("h", topFrame.variables.find { it.name == "c" }!!.value)
                        assertEquals(
                            "java.lang.Character",
                            topFrame.variables.find { it.name == "c" }!!.liveClazz
                        )

                        //string
                        assertEquals("hi", topFrame.variables.find { it.name == "s" }!!.value)
                        assertEquals(
                            "java.lang.String",
                            topFrame.variables.find { it.name == "s" }!!.liveClazz
                        )

                        //double
                        assertEquals(0.23, topFrame.variables.find { it.name == "d" }!!.value)
                        assertEquals(
                            "java.lang.Double",
                            topFrame.variables.find { it.name == "d" }!!.liveClazz
                        )

                        //bool
                        assertEquals(true, topFrame.variables.find { it.name == "bool" }!!.value)
                        assertEquals(
                            "java.lang.Boolean",
                            topFrame.variables.find { it.name == "bool" }!!.liveClazz
                        )

                        //long
                        assertEquals(Long.MAX_VALUE, topFrame.variables.find { it.name == "max" }!!.value)
                        assertEquals(
                            "java.lang.Long",
                            topFrame.variables.find { it.name == "max" }!!.liveClazz
                        )

                        //short
                        assertEquals(
                            Short.MIN_VALUE.toInt(),
                            topFrame.variables.find { it.name == "sh" }!!.value
                        )
                        assertEquals(
                            "java.lang.Short",
                            topFrame.variables.find { it.name == "sh" }!!.liveClazz
                        )

                        //float
                        assertEquals(1.0, topFrame.variables.find { it.name == "f" }!!.value)
                        assertEquals(
                            "java.lang.Float",
                            topFrame.variables.find { it.name == "f" }!!.liveClazz
                        )

                        //integer
                        assertEquals(1, topFrame.variables.find { it.name == "i" }!!.value)
                        assertEquals(
                            "java.lang.Integer",
                            topFrame.variables.find { it.name == "i" }!!.liveClazz
                        )

                        //int arr
                        topFrame.variables.find { it.name == "iArr" }!!.let { iArr ->
                            assertEquals("int[]", iArr.liveClazz)
                            assertEquals(
                                "[1, 2, 3]",
                                (iArr.value as List<Map<String, Any>>).map { it["value"] }.toString()
                            )
                        }

                        //int list
                        topFrame.variables.find { it.name == "iList" }!!.let { iList ->
                            assertEquals("java.util.ArrayList", iList.liveClazz)
                            assertEquals(
                                "[1, 2, 3]",
                                (iList.value as List<Map<String, Any>>).map { it["value"] }.toString()
                            )
                        }
                    }
                }
                else -> testContext.failNow("Got event: " + it.body())
            }

            if (gotAdded && gotHit && gotRemoved) {
                consumer.unregister {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            }
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause())
                return@completionHandler
            }

            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    id = instrumentId,
                    location = LiveSourceLocation("spp.example.webapp.test.InstrumentTest", 44)
                )
            ).onFailure {
                testContext.failNow(it)
            }
        }

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                consumer.unregister()
                log.info("Got added: $gotAdded")
                log.info("Got applied: $gotApplied")
                log.info("Got hit: $gotHit")
                log.info("Got removed: $gotRemoved")
                throw testContext.causeOfFailure()
            }
        } else {
            consumer.unregister()
            log.info("Got added: $gotAdded")
            log.info("Got applied: $gotApplied")
            log.info("Got hit: $gotHit")
            log.info("Got removed: $gotRemoved")
            throw RuntimeException("Test timed out")
        }
    }
}
