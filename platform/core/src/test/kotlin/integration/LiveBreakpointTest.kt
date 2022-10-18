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
package integration

import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.service.error.LiveInstrumentException
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ExtendWith(VertxExtension::class)
class LiveBreakpointTest : PlatformIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveBreakpointTest::class.java)

    @Test
    fun verifyLiveVariables(): Unit = runBlocking {
        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false
        val instrumentId = "live-breakpoint-test-verify-live-variables"

        val instrumentListener = vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onInstrumentEvent(event: LiveInstrumentEvent) {
                log.info("Got instrument event: {}", event)
            }

            override fun onBreakpointAddedEvent(event: LiveBreakpoint) {
                log.info("Got added")
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }
                gotAdded = true
            }

            override fun onInstrumentAppliedEvent(event: LiveInstrument) {
                log.info("Got applied")
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }
                gotApplied = true
            }

            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                log.info("Got removed")
                testContext.verify {
                    assertEquals(instrumentId, event.liveInstrument.id)
                }
                gotRemoved = true
            }

            override fun onBreakpointHitEvent(event: LiveBreakpointHit) {
                log.info("Got hit")
                testContext.verify {
                    assertEquals(instrumentId, event.breakpointId)
                    assertTrue(event.stackTrace.elements.isNotEmpty())
                    val topFrame = event.stackTrace.elements.first()
                    assertEquals(10, topFrame.variables.size)

                    //byte
                    assertEquals(-2, topFrame.variables.find { it.name == "b" }!!.value)
                    assertEquals("java.lang.Byte", topFrame.variables.find { it.name == "b" }!!.liveClazz)

                    //char
                    assertEquals("h", topFrame.variables.find { it.name == "c" }!!.value)
                    assertEquals("java.lang.Character", topFrame.variables.find { it.name == "c" }!!.liveClazz)

                    //string
                    assertEquals("hi", topFrame.variables.find { it.name == "s" }!!.value)
                    assertEquals("java.lang.String", topFrame.variables.find { it.name == "s" }!!.liveClazz)

                    //double
                    assertEquals(0.23, topFrame.variables.find { it.name == "d" }!!.value)
                    assertEquals("java.lang.Double", topFrame.variables.find { it.name == "d" }!!.liveClazz)

                    //bool
                    assertEquals(true, topFrame.variables.find { it.name == "bool" }!!.value)
                    assertEquals("java.lang.Boolean", topFrame.variables.find { it.name == "bool" }!!.liveClazz)

                    //long
                    assertEquals(Long.MAX_VALUE, topFrame.variables.find { it.name == "max" }!!.value)
                    assertEquals("java.lang.Long", topFrame.variables.find { it.name == "max" }!!.liveClazz)

                    //short
                    assertEquals(Short.MIN_VALUE.toInt(), topFrame.variables.find { it.name == "sh" }!!.value)
                    assertEquals("java.lang.Short", topFrame.variables.find { it.name == "sh" }!!.liveClazz)

                    //float
                    assertEquals(1.0, topFrame.variables.find { it.name == "f" }!!.value)
                    assertEquals("java.lang.Float", topFrame.variables.find { it.name == "f" }!!.liveClazz)

                    //integer
                    assertEquals(1, topFrame.variables.find { it.name == "i" }!!.value)
                    assertEquals("java.lang.Integer", topFrame.variables.find { it.name == "i" }!!.liveClazz)
                }
                gotHit = true
            }

            override fun afterInstrumentEvent(event: LiveInstrumentEvent) {
                if (gotAdded && gotHit && gotRemoved) {
                    testContext.completeNow()
                }
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = instrumentId,
                location = LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 25),
            )
        ).onFailure {
            testContext.failNow(it)
        }

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            instrumentListener.unregister()

            if (testContext.failed()) {
                log.info("Got added: $gotAdded")
                log.info("Got applied: $gotApplied")
                log.info("Got hit: $gotHit")
                log.info("Got removed: $gotRemoved")
                throw testContext.causeOfFailure()
            }
        } else {
            instrumentListener.unregister()
            log.info("Got added: $gotAdded")
            log.info("Got applied: $gotApplied")
            log.info("Got hit: $gotHit")
            log.info("Got removed: $gotRemoved")
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun addHitRemove(): Unit = runBlocking {
        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false
        val instrumentId = "live-breakpoint-test-add-hit-remove"

        val instrumentListener = vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onInstrumentEvent(event: LiveInstrumentEvent) {
                log.info("Got instrument event: {}", event)
            }

            override fun onBreakpointAddedEvent(event: LiveBreakpoint) {
                log.info("Got added")
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }
                gotAdded = true
            }

            override fun onInstrumentAppliedEvent(event: LiveInstrument) {
                log.info("Got applied")
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }
                gotApplied = true
            }

            override fun onBreakpointHitEvent(event: LiveBreakpointHit) {
                log.info("Got hit")
                testContext.verify {
                    assertEquals(instrumentId, event.breakpointId)
                }
                gotHit = true
            }

            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                log.info("Got removed")
                testContext.verify {
                    assertEquals(instrumentId, event.liveInstrument.id)
                }
                gotRemoved = true
            }

            override fun afterInstrumentEvent(event: LiveInstrumentEvent) {
                if (gotAdded && gotHit && gotRemoved) {
                    testContext.completeNow()
                }
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = instrumentId,
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "2==2"
            )
        ).onFailure {
            testContext.failNow(it)
        }

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            instrumentListener.unregister()

            if (testContext.failed()) {
                log.info("Got added: $gotAdded")
                log.info("Got applied: $gotApplied")
                log.info("Got hit: $gotHit")
                log.info("Got removed: $gotRemoved")
                throw testContext.causeOfFailure()
            }
        } else {
            instrumentListener.unregister()
            log.info("Got added: $gotAdded")
            log.info("Got applied: $gotApplied")
            log.info("Got hit: $gotHit")
            log.info("Got removed: $gotRemoved")
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun removeById() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = "live-breakpoint-test-remove-by-id",
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2"
            )
        ).onComplete {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstrument(originalId).onComplete {
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

        errorOnTimeout(testContext)
    }

    @Test
    fun removeByLocation() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = "live-breakpoint-test-remove-by-location",
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2"
            )
        ).onComplete {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstruments(
                    LiveSourceLocation("spp.example.webapp.model.User", 42)
                ).onComplete {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(1, it.result().size)
                            assertEquals(originalId, it.result()!![0].id!!)
                            testContext.completeNow()
                        }
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun removeMultipleByLocation(): Unit = runBlocking {
        val testContext = VertxTestContext()

        //todo: don't care about added event. can remove directly after add but need #537
        val addedCount = AtomicInteger(0)
        vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onBreakpointAddedEvent(event: LiveBreakpoint) {
                if (addedCount.incrementAndGet() == 2) {
                    instrumentService.removeLiveInstruments(
                        LiveSourceLocation("spp.example.webapp.model.User", 42)
                    ).onComplete {
                        if (it.succeeded()) {
                            testContext.verify {
                                assertEquals(2, it.result().size)
                                testContext.completeNow()
                            }
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
                }
            }
        }).await()

        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    id = "live-breakpoint-test-remove-multiple-by-location-1",
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    condition = "1==2"
                ),
                LiveBreakpoint(
                    id = "live-breakpoint-test-remove-multiple-by-location-2",
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    condition = "1==3"
                )
            )
        ).onComplete {
            if (it.succeeded()) {
                testContext.verify { assertEquals(2, it.result().size) }
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun addBreakpointWithInvalidCondition() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = "live-breakpoint-test-invalid-condition",
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1===2",
                applyImmediately = true
            )
        ).onComplete {
            if (it.failed()) {
                val cause = ServiceExceptionConverter.fromEventBusException(it.cause().message!!)
                if (cause is LiveInstrumentException) {
                    testContext.verify {
                        assertEquals(
                            "Expression [1===2] @1: EL1042E: Problem parsing right operand",
                            cause.message
                        )
                    }
                    testContext.completeNow()
                } else {
                    testContext.failNow(it.cause().cause ?: it.cause())
                }
            }
        }

        errorOnTimeout(testContext)
    }

    @RepeatedTest(2) //ensures can try again (in case things have changed on probe side)
    fun applyImmediatelyWithInvalidClass() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = "live-breakpoint-test-invalid-class",
                location = LiveSourceLocation("bad.Clazz", 48),
                applyImmediately = true
            )
        ).onComplete {
            if (it.failed()) {
                testContext.verify {
                    assertNotNull(it.cause())
                    val serviceException = ServiceExceptionConverter.fromEventBusException(it.cause().message!!)
                    assertTrue(serviceException is LiveInstrumentException)
                    val ex = serviceException as LiveInstrumentException
                    assertEquals(LiveInstrumentException.ErrorType.CLASS_NOT_FOUND, ex.errorType)
                    assertEquals("bad.Clazz", ex.message)
                }
                testContext.completeNow()
            }
        }

        errorOnTimeout(testContext)
    }
}
