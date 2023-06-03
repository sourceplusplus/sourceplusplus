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
package integration.log

import integration.LiveInstrumentIntegrationTest
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.event.*
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.service.error.LiveInstrumentException
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener

class LiveLogTest : LiveInstrumentIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveLogTest::class.java)

    private fun doTest() {
        startEntrySpan("doTest")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun addAppliedHitRemove(): Unit = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false

        vertx.addLiveInstrumentListener(testNameAsInstrumentId, object : LiveInstrumentListener {
            override fun onLogAddedEvent(event: LiveInstrumentAdded) {
                if (gotAdded) {
                    testContext.failNow("Got added twice")
                }
                log.info("Got added")
                gotAdded = true
            }

            override fun onInstrumentAppliedEvent(event: LiveInstrumentApplied) {
                if (gotApplied) {
                    testContext.failNow("Got applied twice")
                }
                log.info("Got applied")
                gotApplied = true
            }

            override fun onLogHitEvent(event: LiveLogHit) {
                if (gotHit) {
                    testContext.failNow("Got hit twice")
                }
                log.info("Got hit")
                gotHit = true
            }

            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                if (gotRemoved) {
                    testContext.failNow("Got removed twice")
                }
                log.info("Got removed")
                gotRemoved = true
            }

            override fun afterInstrumentEvent(event: LiveInstrumentEvent) {
                if (gotAdded && gotApplied && gotHit && gotRemoved) {
                    testContext.completeNow()
                }
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveLog(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    LiveLogTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                logFormat = "addHitRemove",
                applyImmediately = true
            )
        ).await()

        doTest()

        errorOnTimeout(testContext)
    }

    @Test
    fun removeLogById(): Unit = runBlocking {
        val testContext = VertxTestContext()
        vertx.addLiveInstrumentListener(testNameAsInstrumentId, object : LiveInstrumentListener {
            override fun onLogAddedEvent(event: LiveInstrumentAdded) {
                log.info("Got added event: {}", event)
                instrumentService.removeLiveInstrument(testNameAsInstrumentId).onComplete {
                    if (it.failed()) {
                        testContext.failNow(it.cause())
                    }
                }
            }

            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                log.info("Got removed event: {}", event)
                testContext.completeNow()
            }
        }).await()

        val instrument = instrumentService.addLiveInstrument(
            LiveLog(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    "FakeClass",
                    4,
                    "spp-test-probe"
                ),
                condition = "1==2",
                logFormat = "removeById"
            )
        ).await()
        assertEquals(testNameAsInstrumentId, instrument.id!!)
        log.info("Added instrument: {}", instrument)

        errorOnTimeout(testContext)
    }

    @Test
    fun removeByLocation(): Unit = runBlocking {
        instrumentService.addLiveInstrument(
            LiveLog(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation("bad.Clazz", 133),
                condition = "1==2",
                logFormat = "removeByLocation"
            )
        ).await()

        val removedInstruments = instrumentService.removeLiveInstruments(
            location = LiveSourceLocation("bad.Clazz", 133),
        ).await()

        assertEquals(1, removedInstruments.size)
        assertEquals(testNameAsInstrumentId, removedInstruments[0].id!!)
    }

    @Test
    fun `remove multiple live logs by location`() {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        instrumentService.addLiveInstruments(
            listOf(
                LiveLog(
                    id = "$testNameAsInstrumentId-1",
                    location = LiveSourceLocation(
                        LiveLogTest::class.java.name,
                        100,
                        "spp-test-probe"
                    ),
                    condition = "1==2",
                    logFormat = "removeMultipleByLocation"
                ),
                LiveLog(
                    id = "$testNameAsInstrumentId-2",
                    location = LiveSourceLocation(
                        LiveLogTest::class.java.name,
                        100,
                        "spp-test-probe"
                    ),
                    condition = "1==3",
                    logFormat = "removeMultipleByLocation"
                )
            )
        ).onComplete {
            if (it.succeeded()) {
                testContext.verify { assertEquals(2, it.result().size) }
                instrumentService.removeLiveInstruments(
                    location = LiveSourceLocation(
                        LiveLogTest::class.java.name,
                        100,
                        "spp-test-probe"
                    )
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
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun addLogWithInvalidCondition() {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveLog(
                location = LiveSourceLocation(
                    LiveLogTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                condition = "1===2",
                logFormat = "addLogWithInvalidCondition",
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
                        testContext.completeNow()
                    }
                } else {
                    testContext.failNow(it.cause().cause ?: it.cause())
                }
            }
        }

        errorOnTimeout(testContext)
    }

    @RepeatedTest(2) //ensures can try again (in case things have changed on probe side)
    fun applyImmediatelyWithInvalidClass(): Unit = runBlocking {
        val testContext = VertxTestContext()
        vertx.addLiveInstrumentListener(testNameAsInstrumentId, object : LiveInstrumentListener {
            override fun onLogAddedEvent(event: LiveInstrumentAdded) {
                testContext.verify {
                    assertEquals(testNameAsInstrumentId, event.instrument.id)
                }
                testContext.completeNow()
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveLog(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation("bad.Clazz", 48),
                logFormat = "applyImmediatelyWithInvalidClass",
                applyImmediately = true
            )
        ).onComplete {
            if (it.failed()) {
                testContext.verify {
                    val cause = ServiceExceptionConverter.fromEventBusException(it.cause().message!!)
                    assertTrue(cause is LiveInstrumentException)
                    val ex = cause as LiveInstrumentException
                    assertEquals(LiveInstrumentException.ErrorType.CLASS_NOT_FOUND, ex.errorType)
                    assertEquals("bad.Clazz", ex.message)
                }
            }
        }

        errorOnTimeout(testContext)
    }
}
