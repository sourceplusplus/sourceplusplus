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
package integration.log

import integration.LiveInstrumentIntegrationTest
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
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
    fun addHitRemove() = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        var gotAdded = false
        var gotApplied = false
        var gotHit = false
        var gotRemoved = false
        val instrumentId = "live-log-test-add-hit-remove"

        val consumer = vertx.eventBus().consumer<JsonObject>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.LOG_ADDED -> {
                    log.info("Got added")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotAdded = true
                }

                LiveInstrumentEventType.LOG_APPLIED -> {
                    log.info("Got applied")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("id"))
                    }
                    gotApplied = true
                }

                LiveInstrumentEventType.LOG_HIT -> {
                    log.info("Got hit")
                    testContext.verify {
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("logId"))
                    }
                    gotHit = true
                }

                LiveInstrumentEventType.LOG_REMOVED -> {
                    log.info("Got removed")
                    testContext.verify {
                        val remEvent = LiveInstrumentRemoved(JsonObject(liveEvent.data))
                        assertEquals(instrumentId, remEvent.liveInstrument.id)
                    }
                    gotRemoved = true
                }

                else -> testContext.failNow("Got event: " + it.body())
            }

            if (gotAdded && gotApplied && gotHit && gotRemoved) {
                consumer.unregister {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            }
        }.completionHandler().await()

        instrumentService.addLiveInstrument(
            LiveLog(
                id = instrumentId,
                location = LiveSourceLocation(
                    LiveLogTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                logFormat = "addHitRemove",
                applyImmediately = true
            )
        ).await()

        delay(2000)
        doTest()

        errorOnTimeout(testContext)
    }

    @Test
    fun removeById(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = "live-log-test-remove-by-id"

        vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onLogAddedEvent(event: LiveLog) {
                log.info("Got added event: {}", event)
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }

                instrumentService.removeLiveInstrument(instrumentId).onComplete {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(instrumentId, it.result()!!.id!!)
                        }
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            }

            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                log.info("Got removed event: {}", event)
                testContext.verify {
                    assertEquals(instrumentId, event.liveInstrument.id!!)
                }
                testContext.completeNow()
            }
        }).await()

        val instrument = instrumentService.addLiveInstrument(
            LiveLog(
                id = instrumentId,
                location = LiveSourceLocation(
                    LiveLogTest::class.qualifiedName!!,
                    4,
                    "spp-test-probe"
                ),
                condition = "1==2",
                logFormat = "removeById"
            )
        ).await()
        assertEquals(instrumentId, instrument.id!!)
        log.info("Added instrument: {}", instrument)

        errorOnTimeout(testContext)
    }

    @Test
    fun removeByLocation(): Unit = runBlocking {
        val instrument = instrumentService.addLiveInstrument(
            LiveLog(
                id = "live-log-test-remove-by-location",
                location = LiveSourceLocation("bad.Clazz", 133),
                condition = "1==2",
                logFormat = "removeByLocation"
            )
        ).await()

        val originalId = instrument.id!!
        val removedInstruments = instrumentService.removeLiveInstruments(
            location = LiveSourceLocation("bad.Clazz", 133),
        ).await()

        assertEquals(1, removedInstruments.size)
        assertEquals(originalId, removedInstruments[0].id!!)
    }

    @Test
    fun removeMultipleByLocation() {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        instrumentService.addLiveInstruments(
            listOf(
                LiveLog(
                    id = "live-log-test-remove-multiple-by-location-1",
                    location = LiveSourceLocation(
                        LiveLogTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        "spp-test-probe"
                    ),
                    condition = "1==2",
                    logFormat = "removeMultipleByLocation"
                ),
                LiveLog(
                    id = "live-log-test-remove-multiple-by-location-2",
                    location = LiveSourceLocation(
                        LiveLogTest::class.qualifiedName!!,
                        getLineNumber("done"),
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
                        LiveLogTest::class.qualifiedName!!,
                        getLineNumber("done"),
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
                id = "live-log-test-invalid-condition",
                location = LiveSourceLocation(
                    LiveLogTest::class.qualifiedName!!,
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
        val instrumentId = "live-log-test-invalid-class"

        //todo: don't care about added event. can remove directly after add but need #537
        vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onLogAddedEvent(event: LiveLog) {
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }

                testContext.completeNow()
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveLog(
                id = instrumentId,
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
