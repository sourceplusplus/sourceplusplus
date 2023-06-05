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
package integration.breakpoint

import integration.LiveInstrumentIntegrationTest
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.*
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.service.error.LiveInstrumentException
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveBreakpointTest::class.java)

    @Suppress("UNUSED_VARIABLE")
    private fun doTest() {
        startEntrySpan("doTest")
        val i = 1
        val c = 'h'
        val s = "hi"
        val f = 1.0f
        val max = Long.MAX_VALUE
        val b: Byte = -2
        val sh = Short.MIN_VALUE
        val d = 00.23
        val bool = true
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun verifyLiveVariables(): Unit = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false

        val instrumentListener = vertx.addLiveInstrumentListener(
            testNameAsInstrumentId,
            object : LiveInstrumentListener {
                override fun onBreakpointAddedEvent(event: LiveInstrumentAdded) {
                    log.info("Got added")
                    gotAdded = true
                }

                override fun onInstrumentAppliedEvent(event: LiveInstrumentApplied) {
                    log.info("Got applied")
                    gotApplied = true
                }

                override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                    log.info("Got removed")
                    gotRemoved = true
                }

                override fun onBreakpointHitEvent(event: LiveBreakpointHit) {
                    log.info("Got hit")
                    testContext.verify {
                        assertEquals(testNameAsInstrumentId, event.instrument.id)
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
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    LiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        doTest()

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
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false

        val instrumentListener = vertx.addLiveInstrumentListener(
            testNameAsInstrumentId,
            object : LiveInstrumentListener {
                override fun onBreakpointAddedEvent(event: LiveInstrumentAdded) {
                    log.info("Got added")
                    gotAdded = true
                }

                override fun onInstrumentAppliedEvent(event: LiveInstrumentApplied) {
                    log.info("Got applied")
                    gotApplied = true
                }

                override fun onBreakpointHitEvent(event: LiveBreakpointHit) {
                    log.info("Got hit")
                    gotHit = true
                }

                override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                    log.info("Got removed")
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
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    LiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                condition = "2==2",
                applyImmediately = true
            )
        ).await()

        doTest()

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
    fun removeBreakpointById() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
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
                location = LiveSourceLocation("RemoveByLocation", 42),
                condition = "1==2"
            )
        ).onComplete {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstruments(
                    LiveSourceLocation("RemoveByLocation", 42)
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
    fun `remove multiple bps by location`(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val addedCount = AtomicInteger(0)
        val listener = object : LiveInstrumentListener {
            override fun onBreakpointAddedEvent(event: LiveInstrumentAdded) {
                if (addedCount.incrementAndGet() == 2) {
                    instrumentService.removeLiveInstruments(
                        LiveSourceLocation(testName, 42)
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
        }
        vertx.addLiveInstrumentListener("$testNameAsInstrumentId-1", listener).await()
        vertx.addLiveInstrumentListener("$testNameAsInstrumentId-2", listener).await()

        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    id = "$testNameAsInstrumentId-1",
                    location = LiveSourceLocation(testName, 42),
                    condition = "1==2"
                ),
                LiveBreakpoint(
                    id = "$testNameAsInstrumentId-2",
                    location = LiveSourceLocation(testName, 42),
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
