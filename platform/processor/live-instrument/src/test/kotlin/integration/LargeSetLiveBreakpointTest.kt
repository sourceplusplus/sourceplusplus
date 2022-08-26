/*
 * Source++, the open-source live coding platform.
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

import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("unused")
class LargeSetLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun largeSet() {
        startEntrySpan("largeSet")
        val largeSet = HashSet<Int>()
        for (i in 0 until 100_000) {
            largeSet.add(i)
        }
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `large set`() {
        setupLineLabels {
            largeSet()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //largeSet
                val largeSetVariable = topFrame.variables.first { it.name == "largeSet" }
                assertNotNull(largeSetVariable)
                assertEquals(
                    "java.util.HashSet",
                    largeSetVariable.liveClazz
                )

                val setValues = largeSetVariable.value as List<Map<String, Any>>
                assertEquals(101, setValues.size)
                for ((index, value) in setValues.subList(0, 100).withIndex()) {
                    assertEquals(index, value["value"])
                }
                val lastValue = setValues.last()["value"] as Map<String, Any>
                assertEquals("MAX_COLLECTION_SIZE_EXCEEDED", lastValue["@skip"])
                assertEquals(100_000, lastValue["@skip[size]"])
                assertEquals(100, lastValue["@skip[max]"])
            }

            //test passed
            testContext.completeNow()
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause())
                return@completionHandler
            }

            //add live breakpoint
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        LargeSetLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    largeSet()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
