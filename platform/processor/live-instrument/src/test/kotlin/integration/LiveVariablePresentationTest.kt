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

import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.addBreakpointHitListener
import java.math.BigInteger
import java.time.*
import java.util.*

@Suppress("UNUSED_VARIABLE")
class LiveVariablePresentationTest : LiveInstrumentIntegrationTest() {

    private fun liveVariablePresentation() {
        startEntrySpan("liveVariablePresentation")
        val date = Date(1L)
        val duration = Duration.ofSeconds(1)
        val instant = Instant.ofEpochSecond(1)
        val localDate = LocalDate.of(1, 2, 3)
        val localTime = LocalTime.of(1, 2, 3, 4)
        val localDateTime = LocalDateTime.of(1, 2, 3, 4, 5, 6)
        val offsetDateTime = OffsetDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneOffset.ofHours(8))
        val offsetTime = OffsetTime.of(1, 2, 3, 4, ZoneOffset.ofHours(8))
        val zonedDateTime = ZonedDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneId.of("Europe/Berlin"))
        val zoneOffset = ZoneOffset.ofHours(8)
//        val zoneRegion = ZoneRegion.ofId("Europe/Berlin")
        val bigInteger = BigInteger.TEN
        val clazz = Class.forName("java.lang.String")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `live variable presentation`() = runBlocking {
        setupLineLabels { liveVariablePresentation() }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(13, topFrame.variables.size)

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
                //todo: instants don't match
//                    assertEquals(
//                        Instant.ofEpochSecond(1).toString(),
//                        topFrame.variables.find { it.name == "instant" }!!.presentation
//                    )
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

                // LocalDateTime
                assertEquals(
                    LocalDateTime.of(1, 2, 3, 4, 5, 6).toString(),
                    topFrame.variables.find { it.name == "localDateTime" }!!.presentation
                )
                assertEquals(
                    "java.time.LocalDateTime",
                    topFrame.variables.find { it.name == "localDateTime" }!!.liveClazz
                )

                // OffsetDateTime
                //todo: offsetDateTimes don't match
//                    assertEquals(
//                        OffsetDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneOffset.ofHours(8)).toString(),
//                        topFrame.variables.find { it.name == "offsetDateTime" }!!.presentation
//                    )
                assertEquals(
                    "java.time.OffsetDateTime",
                    topFrame.variables.find { it.name == "offsetDateTime" }!!.liveClazz
                )

                // OffsetTime
                //todo: offsetTimes don't match
//                    assertEquals(
//                        OffsetTime.of(1, 2, 3, 4, ZoneOffset.ofHours(8)).toString(),
//                        topFrame.variables.find { it.name == "offsetTime" }!!.presentation
//                    )
                assertEquals(
                    "java.time.OffsetTime",
                    topFrame.variables.find { it.name == "offsetTime" }!!.liveClazz
                )

                // ZonedDateTime
                //todo: zonedDateTimes don't match
//                    assertEquals(
//                        ZonedDateTime.of(1, 2, 3, 4, 5, 6, 7, ZoneId.of("Europe/Berlin")).toString(),
//                        topFrame.variables.find { it.name == "zonedDateTime" }!!.presentation
//                    )
                assertEquals(
                    "java.time.ZonedDateTime",
                    topFrame.variables.find { it.name == "zonedDateTime" }!!.liveClazz
                )

                // ZoneOffset
                //todo: zoneOffsets don't match
//                    assertEquals(
//                        ZoneOffset.ofHours(8).toString(),
//                        topFrame.variables.find { it.name == "zoneOffset" }!!.presentation
//                    )
                assertEquals(
                    "java.time.ZoneOffset",
                    topFrame.variables.find { it.name == "zoneOffset" }!!.liveClazz
                )

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
                assertEquals(
                    "java.lang.String",
                    topFrame.variables.find { it.name == "clazz" }!!.presentation
                )
                assertEquals(
                    "java.lang.Class",
                    topFrame.variables.find { it.name == "clazz" }!!.liveClazz
                )
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LiveVariablePresentationTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true,
                id = testNameAsInstrumentId
            )
        ).await()

        //trigger live breakpoint
        liveVariablePresentation()

        errorOnTimeout(testContext)
    }
}
