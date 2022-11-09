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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

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
    fun `large set`() = runBlocking {
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

                val setValues = largeSetVariable.value as JsonArray
                assertEquals(101, setValues.size())
                for (index in 0..99) {
                    val value = setValues.getJsonObject(index)
                    assertEquals(index, value.getInteger("value"))
                }
                val lastValue = (setValues.last() as JsonObject).getJsonObject("value")
                assertEquals("MAX_LENGTH_EXCEEDED", lastValue.getString("@skip"))
                assertEquals(100_000, lastValue.getInteger("@skip[size]"))
                assertEquals(100, lastValue.getInteger("@skip[max]"))
            }

            //test passed
            testContext.completeNow()
        }.completionHandler().await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LargeSetLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        largeSet()

        errorOnTimeout(testContext)
    }
}
