package integration

import spp.protocol.SourceMarkerServices
import spp.protocol.SourceMarkerServices.Provide
import spp.protocol.instrument.LiveInstrumentBatch
import spp.protocol.instrument.LiveInstrumentEvent
import spp.protocol.instrument.LiveInstrumentEventType
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.breakpoint.event.LiveBreakpointHit
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
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class LiveInstrumentTest : IntegrationTest() {

    private val log = LoggerFactory.getLogger(LiveInstrumentTest::class.java)

    @Test
    fun getLiveInstrumentById_missing() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.getLiveInstrumentById("whatever") {
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
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(LiveSourceLocation("integration.LiveInstrumentTest", 1))
        ) {
            if (it.succeeded()) {
                val originalId = it.result().id!!
                instrumentService.getLiveInstrumentById(originalId) {
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
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstruments(
            LiveInstrumentBatch(
                listOf(
                    LiveBreakpoint(LiveSourceLocation("integration.LiveInstrumentTest", 1)),
                    LiveBreakpoint(LiveSourceLocation("integration.LiveInstrumentTest", 2))
                )
            )
        ) {
            if (it.succeeded()) {
                val originalIds = it.result().map { it.id!! }
                instrumentService.getLiveInstrumentsByIds(originalIds) {
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
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = Json.decodeValue(liveEvent.data, LiveBreakpointHit::class.java)
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments {
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
        ) {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 16),
                        applyImmediately = true
                    )
                ) {
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
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = Json.decodeValue(liveEvent.data, LiveBreakpointHit::class.java)
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments {
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
        ) {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.controller.LiveInstrumentController", 16),
                        applyImmediately = true
                    )
                ) {
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
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = Json.decodeValue(liveEvent.data, LiveBreakpointHit::class.java)
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments {
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
        ) {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.edge.SingleThread", 28),
                        applyImmediately = true
                    )
                ) {
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
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        val consumer = vertx.eventBus().localConsumer<JsonObject>("local." + Provide.LIVE_INSTRUMENT_SUBSCRIBER)
        consumer.handler {
            log.info("Got subscription event: {}", it.body())
            val liveEvent = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            when (liveEvent.eventType) {
                LiveInstrumentEventType.BREAKPOINT_HIT -> {
                    log.info("Got hit")
                    val bpHit = Json.decodeValue(liveEvent.data, LiveBreakpointHit::class.java)
                    testContext.verify {
                        assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                        val topFrame = bpHit.stackTrace.elements.first()
                        assertEquals(1, topFrame.variables.size)
                    }

                    instrumentService.clearLiveInstruments {
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
        ) {
            if (it.succeeded()) {
                instrumentService.addLiveInstrument(
                    LiveBreakpoint(
                        LiveSourceLocation("spp.example.webapp.edge.SingleThread", 28),
                        applyImmediately = true
                    )
                ) {
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
