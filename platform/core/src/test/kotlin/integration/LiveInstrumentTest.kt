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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class LiveInstrumentTest : PlatformIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveInstrumentTest::class.java)

    @Test
    fun getLiveInstrumentById_missing() {
        val testContext = VertxTestContext()
        instrumentService.getLiveInstrumentById("whatever").onComplete {
            if (it.succeeded()) {
                testContext.verify {
                    assertNull(it.result())
                }
                testContext.completeNow()
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun getLiveInstrumentById() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(LiveSourceLocation("integration.LiveInstrumentTest", 1))
        ).onComplete {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.getLiveInstrumentById(originalId).onComplete {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(originalId, it.result()!!.id!!)
                        }
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun getLiveInstrumentByIds() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(LiveSourceLocation("integration.LiveInstrumentTest", 1)),
                LiveBreakpoint(LiveSourceLocation("integration.LiveInstrumentTest", 2))
            )
        ).onComplete {
            if (it.succeeded()) {
                val originalIds = it.result().map { it.id!! }
                instrumentService.getLiveInstrumentsByIds(originalIds).onComplete {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(2, it.result()!!.size)
                            assertEquals(2, originalIds.size)
                            assertTrue(it.result()[0].id!! in originalIds)
                            assertTrue(it.result()[1].id!! in originalIds)
                        }
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint() {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = LiveBreakpointHit(JsonObject(liveEvent.data))
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            consumer.unregister {
                                if (it.succeeded()) {
                                    testContext.completeNow()
                                } else {
                                    testContext.failNow(it.cause())
                                }
                            }
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
                }
            }
        }

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 25),
                hitLimit = Int.MAX_VALUE,
                applyImmediately = true
            )
        ).onComplete {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 16),
                        applyImmediately = true
                    )
                ).onComplete {
                    if (it.failed()) {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint_noLogHit() {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = LiveBreakpointHit(JsonObject(liveEvent.data))
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            consumer.unregister {
                                if (it.succeeded()) {
                                    testContext.completeNow()
                                } else {
                                    testContext.failNow(it.cause())
                                }
                            }
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
                }
            }
        }

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 25),
                "1==2",
                applyImmediately = true
            )
        ).onComplete {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 16),
                        applyImmediately = true
                    )
                ).onComplete {
                    if (it.failed()) {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint_singledThreaded() {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = LiveBreakpointHit(JsonObject(liveEvent.data))
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            consumer.unregister {
                                if (it.succeeded()) {
                                    testContext.completeNow()
                                } else {
                                    testContext.failNow(it.cause())
                                }
                            }
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
                }
            }
        }

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                LiveSourceLocation("spp.example.webapp.edge.SingleThread", 37),
                hitLimit = Int.MAX_VALUE,
                applyImmediately = true
            )
        ).onComplete {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.edge.SingleThread", 28),
                        applyImmediately = true
                    )
                ).onComplete {
                    if (it.failed()) {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint_singledThreaded_noLogHit() {
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = LiveBreakpointHit(JsonObject(liveEvent.data))
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            consumer.unregister {
                                if (it.succeeded()) {
                                    testContext.completeNow()
                                } else {
                                    testContext.failNow(it.cause())
                                }
                            }
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
                }
            }
        }

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                LiveSourceLocation("spp.example.webapp.edge.SingleThread", 37),
                "1==2",
                applyImmediately = true
            )
        ).onComplete {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.edge.SingleThread", 28),
                        applyImmediately = true
                    )
                ).onComplete {
                    if (it.failed()) {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
