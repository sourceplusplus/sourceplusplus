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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("unused")
class LargeListLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun largeList() {
        startEntrySpan("largeList")
        val largeList = ArrayList<Int>()
        for (i in 0 until 100_000) {
            largeList.add(1)
        }
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `large list`() {
        setupLineLabels {
            largeList()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //largeList
                val largeListVariable = topFrame.variables.first { it.name == "largeList" }
                assertNotNull(largeListVariable)
                assertEquals(
                    "java.util.ArrayList",
                    largeListVariable.liveClazz
                )

                val listValues = largeListVariable.value as JsonArray
                assertEquals(101, listValues.size())

                for (i in 0..99) {
                    val value = listValues.getJsonObject(i)
                    assertEquals(1, value.getInteger("value"))
                }
                val lastValue = (listValues.last() as JsonObject).getJsonObject("value")
                assertEquals("MAX_COLLECTION_SIZE_EXCEEDED", lastValue.getString("@skip"))
                assertEquals(100_000, lastValue.getInteger("@skip[size]"))
                assertEquals(100, lastValue.getInteger("@skip[max]"))
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
                        LargeListLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    largeList()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
