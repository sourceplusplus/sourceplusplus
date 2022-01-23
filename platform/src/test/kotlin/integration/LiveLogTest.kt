package integration

import spp.protocol.SourceMarkerServices
import spp.protocol.SourceMarkerServices.Provide
import spp.protocol.instrument.LiveInstrumentBatch
import spp.protocol.instrument.LiveInstrumentEvent
import spp.protocol.instrument.LiveInstrumentEventType
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.log.LiveLog
import spp.protocol.service.live.LiveInstrumentService
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.serviceproxy.ServiceProxyBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.service.error.LiveInstrumentException
import java.util.*
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
        val instrumentId = UUID.randomUUID().toString()

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
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
                        assertEquals(instrumentId, JsonObject(liveEvent.data).getString("logId"))
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
                testContext.failNow(it.cause());
                return@completionHandler
            }

            val instrumentService = ServiceProxyBuilder(vertx)
                .setToken(SYSTEM_JWT_TOKEN)
                .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
                .build(LiveInstrumentService::class.java)
            instrumentService.addLiveInstrument(
                LiveLog(
                    id = instrumentId,
                    location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                    logFormat = "addHitRemove"
                )
            ) {
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
    fun removeById() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveLog(
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2",
                logFormat = "removeById"
            )
        ) {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstrument(originalId) {
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
    fun removeByLocation() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveLog(
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1==2",
                logFormat = "removeByLocation"
            )
        ) {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.removeLiveInstruments(
                    LiveSourceLocation("spp.example.webapp.model.User", 42)
                ) {
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

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun removeMultipleByLocation() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstruments(
            LiveInstrumentBatch(
                listOf(
                    LiveLog(
                        location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                        condition = "1==2",
                        logFormat = "removeMultipleByLocation"
                    ),
                    LiveLog(
                        location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                        condition = "1==3",
                        logFormat = "removeMultipleByLocation"
                    )
                )
            )
        ) {
            if (it.succeeded()) {
                testContext.verify { assertEquals(2, it.result().size) }
                instrumentService.removeLiveInstruments(
                    LiveSourceLocation("spp.example.webapp.model.User", 42)
                ) {
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

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun addLogWithInvalidCondition() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveLog(
                location = LiveSourceLocation("spp.example.webapp.model.User", 42),
                condition = "1===2",
                logFormat = "addLogWithInvalidCondition",
                applyImmediately = true
            )
        ) {
            if (it.failed()) {
                if (it.cause().cause is LiveInstrumentException) {
                    testContext.verify {
                        assertEquals(
                            "Expression [1===2] @1: EL1042E: Problem parsing right operand",
                            it.cause().cause!!.message
                        )
                        testContext.completeNow()
                    }
                } else {
                    testContext.failNow(it.cause().cause ?: it.cause())
                }
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

    @RepeatedTest(2) //ensures can try again (in case things have changed on probe side)
    fun applyImmediatelyWithInvalidClass() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveLog(
                location = LiveSourceLocation("bad.Clazz", 48),
                logFormat = "applyImmediatelyWithInvalidClass",
                applyImmediately = true
            )
        ) {
            if (it.failed()) {
                testContext.verify {
                    assertNotNull(it.cause().cause)
                    assertTrue(it.cause().cause is LiveInstrumentException)
                    val ex = it.cause().cause as LiveInstrumentException
                    assertEquals(LiveInstrumentException.ErrorType.CLASS_NOT_FOUND, ex.errorType)
                    assertEquals("bad.Clazz", it.cause().cause!!.message)
                }
                testContext.completeNow()
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
