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

import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress

class LiveInstrumentTest : LiveInstrumentIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveInstrumentTest::class.java)

    private fun doTest() {
        startEntrySpan("doTest")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun getLiveInstrumentById_missing() {
        val testContext = VertxTestContext()
        instrumentService.getLiveInstrument("whatever").onComplete {
            if (it.succeeded()) {
                testContext.verify {
                    assertNull(it.result())
                }
                testContext.completeNow()
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun getLiveInstrumentById(): Unit = runBlocking {
        val instrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(location = LiveSourceLocation("integration.LiveInstrumentTest", 1))
        ).await()

        val originalId = instrument.id!!
        val getInstrument = instrumentService.getLiveInstrument(originalId).await()
        assertEquals(originalId, getInstrument!!.id!!)

        instrumentService.clearLiveInstruments().await()
    }

    @Test
    fun getLiveInstrumentByIds(): Unit = runBlocking {
        val instrument = instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(location = LiveSourceLocation("integration.LiveInstrumentTest", 1)),
                LiveBreakpoint(location = LiveSourceLocation("integration.LiveInstrumentTest", 2))
            )
        ).await()

        val originalIds = instrument.map { it.id!! }
        val getInstrument = instrumentService.getLiveInstrumentsByIds(originalIds).await()
        assertEquals(2, getInstrument.size)
        assertEquals(2, originalIds.size)
        assertTrue(getInstrument[0].id!! in originalIds)
        assertTrue(getInstrument[1].id!! in originalIds)

        instrumentService.clearLiveInstruments().await()
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint() = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = LiveInstrumentEvent(it.body())
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
        }.completionHandler().await()

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        delay(2000)
        doTest()

        errorOnTimeout(testContext)
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint_noLogHit() = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = LiveInstrumentEvent(it.body())
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = LiveBreakpointHit(JsonObject(liveEvent.data))
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments().onComplete {
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
        }.completionHandler().await()

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                "1==2",
                applyImmediately = true
            )
        ).await()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        delay(2000)
        doTest()

        errorOnTimeout(testContext)
    }
}
