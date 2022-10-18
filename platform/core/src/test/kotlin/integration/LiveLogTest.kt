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

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class LiveLogTest : PlatformIntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveLogTest::class.java)

    @Test
    fun addHitRemove() {
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
                LiveLog(
                    id = instrumentId,
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    logFormat = "addHitRemove"
                )
            ).onComplete {
                if (!it.succeeded()) {
                    testContext.failNow(it.cause())
                }
            }
        }

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
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

    @Test
    fun removeById(): Unit = runBlocking {
        val testContext = VertxTestContext()
        val instrumentId = "live-log-test-remove-by-id"

        //todo: don't care about added event. can remove directly after add but need #537
        vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onLogAddedEvent(event: LiveLog) {
                testContext.verify {
                    assertEquals(instrumentId, event.id)
                }

                instrumentService.removeLiveInstrument(instrumentId).onComplete {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(instrumentId, it.result()!!.id!!)
                        }
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveLog(
                id = instrumentId,
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2",
                logFormat = "removeById"
            )
        ).onComplete {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                testContext.verify {
                    assertEquals(instrumentId, originalId)
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
            LiveLog(
                id = "live-log-test-remove-by-location",
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2",
                logFormat = "removeByLocation"
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
    fun removeMultipleByLocation() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstruments(
            listOf(
                LiveLog(
                    id = "live-log-test-remove-multiple-by-location-1",
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    condition = "1==2",
                    logFormat = "removeMultipleByLocation"
                ),
                LiveLog(
                    id = "live-log-test-remove-multiple-by-location-2",
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    condition = "1==3",
                    logFormat = "removeMultipleByLocation"
                )
            )
        ).onComplete {
            if (it.succeeded()) {
                testContext.verify { assertEquals(2, it.result().size) }
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
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun addLogWithInvalidCondition() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveLog(
                id = "live-log-test-invalid-condition",
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
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
