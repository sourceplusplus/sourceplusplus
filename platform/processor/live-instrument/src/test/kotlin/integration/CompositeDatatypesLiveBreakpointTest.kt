package integration

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.marshall.ProtocolMarshaller
import java.math.BigInteger
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit

@Suppress("UNUSED_VARIABLE")
open class CompositeDatatypesLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun compositeDatatypes() {
        val activeSpan = startEntrySpan("compositeDatatypes")
        val date = Date(1L)
        val duration = Duration.ofSeconds(1)
        val instant = Instant.ofEpochSecond(1)
        val localDate = LocalDate.of(1, 2, 3)
        val localTime = LocalTime.of(1, 2, 3, 4)
//        val offsetDateTime = OffsetDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneOffset.MAX)
//        val offsetTime = OffsetTime.of(1, 2, 3, 4, ZoneOffset.MIN)
//        val zonedDateTime = ZonedDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneId.of("EST"))
//        val zoneOffset = ZoneOffset.ofTotalSeconds(1)
//        val zoneRegion = Zo
        val bigInteger = BigInteger.TEN
//        val clazz = Class.forName("Ezpeepee")

        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan(activeSpan)
    }

    @Test
    fun `composite datatypes`() {
        setupLineLabels { compositeDatatypes() }

        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                //verify live breakpoint data
                val breakpointHit =
                    ProtocolMarshaller.deserializeLiveBreakpointHit(JsonObject(event.data))
                testContext.verify {
                    assertTrue(breakpointHit.stackTrace.elements.isNotEmpty())
                    val topFrame = breakpointHit.stackTrace.elements.first()
                    assertEquals(7, topFrame.variables.size)

                    //todo: Check 1 test

                    // Date
                    assertEquals(
                        Date(1L).toString(),
                        topFrame.variables.find { it.name == "date" }!!.value.let { Date(it.toString()).toString() }
                    )
                    assertEquals(
                        "java.util.Date",
                        topFrame.variables.find { it.name == "date" }!!.liveClazz
                    )

                    // Duration
                    assertEquals(
                        Duration.ofSeconds(1).toString(),
                        topFrame.variables.find { it.name == "duration" }!!.presentation
                    )
                    assertEquals(
                        "java.time.Duration",
                        topFrame.variables.find { it.name == "duration" }!!.liveClazz
                    )

                    // Instant
                    assertEquals(
                        Instant.ofEpochSecond(1).toString(),
                        topFrame.variables.find { it.name == "instant" }!!.presentation
                    )
                    assertEquals(
                        "java.time.Instant",
                        topFrame.variables.find { it.name == "instant" }!!.liveClazz
                    )

                    // LocalDate
                    assertEquals(
                        LocalDate.of(1, 2, 3).toString(),
                        topFrame.variables.find { it.name == "localDate" }!!.presentation
                    )
                    assertEquals(
                        "java.time.LocalDate",
                        topFrame.variables.find { it.name == "localDate" }!!.liveClazz
                    )

                    // LocalTime
                    assertEquals(
                        LocalTime.of(1, 2, 3, 4).toString(),
                        topFrame.variables.find { it.name == "localTime" }!!.presentation
                    )
                    assertEquals(
                        "java.time.LocalTime",
                        topFrame.variables.find { it.name == "localTime" }!!.liveClazz
                    )

                    // LocalTime
                    //todo: test times out
//                    assertEquals(1,1      )
//                    assertEquals(
//                        "java.time.OffsetDateTime",
//                        topFrame.variables.find { it.name == "offsetTime" }!!.liveClazz
//                    )

                    // OffsetTime
                    //todo: test times out
//                    assertEquals(
//                        OffsetTime.of(1, 2, 3, 4, ZoneOffset.MIN),
//                        topFrame.variables.find { it.name == "offsetTime" }!!.presentation
//                    )
//                    assertEquals(
//                        "java.time.OffsetTime",
//                        topFrame.variables.find { it.name == "offsetTime" }!!.liveClazz
//                    )

                    // ZonedDateTime
                    //todo: test times out
//                    assertEquals(
//                        ZonedDateTime.of(1, 2, 3, 4, 5, 6, 7,
//                            ZoneId.of("EST")),
//                        topFrame.variables.find { it.name == "zonedDateTime" }!!.presentation
//                    )
//                    assertEquals(
//                        "java.time.ZonedDateTime",
//                        topFrame.variables.find { it.name == "zonedDateTime" }!!.liveClazz
//                    )

                    // ZoneOffset
                    //todo: test times out
//                    assertEquals(
//                        ZoneOffset.ofTotalSeconds(1).toString(),
//                        topFrame.variables.find { it.name == "zoneOffset" }!!.presentation
//                    )
//                    assertEquals(
//                        "java.time.ZoneOffset",
//                        topFrame.variables.find { it.name == "zoneOffset" }!!.liveClazz
//                    )

                    // BigInteger
                    assertEquals(
                        BigInteger.TEN,
                        topFrame.variables.find { it.name == "bigInteger" }!!.value.let { BigInteger(it.toString()) }
                    )
                    assertEquals(
                        "java.math.BigInteger",
                        topFrame.variables.find { it.name == "bigInteger" }!!.liveClazz
                    )

                    // Class
                    //todo: test times out
//                    assertEquals(
//                        Class.forName("Ezpeepee"),
//                        topFrame.variables.find { it.name == "clazz" }!!.value
//                    )
//                    assertEquals(
//                        "java.math.BigInteger",
//                        topFrame.variables.find { it.name == "clazz" }!!.liveClazz
//                    )


                }

                //test passed
                testContext.completeNow()
            }
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause())
                return@completionHandler
            }

            //add live breakpoint
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        CompositeDatatypesLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                    ),
                )
            ).onSuccess {
                vertx.setTimer(5000) {
                    compositeDatatypes()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
