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

@Suppress("unused")
class LargeMapLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun largeMap() {
        startEntrySpan("largeMap")
        val largeMap = LinkedHashMap<String, String>()
        for (i in 0 until 100_000) {
            largeMap[i.toString()] = i.toString()
        }
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `large map`() = runBlocking {
        setupLineLabels {
            largeMap()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //largeMap
                val largeMapVariable = topFrame.variables.first { it.name == "largeMap" }
                assertNotNull(largeMapVariable)
                assertEquals(
                    "java.util.LinkedHashMap",
                    largeMapVariable.liveClazz
                )

                val mapValues = largeMapVariable.value as JsonObject
                assertEquals(105, mapValues.size())
                for (index in 0..99) {
                    assertEquals(index.toString(), mapValues.getString(index.toString()))
                }
                assertEquals("MAX_LENGTH_EXCEEDED", mapValues.getString("@skip"))
                assertEquals(100_000, mapValues.getInteger("@skip[size]"))
                assertEquals(100, mapValues.getInteger("@skip[max]"))
                assertNotNull(mapValues.getString("@id"))
            }

            //test passed
            testContext.completeNow()
        }.completionHandler().await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LargeMapLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        largeMap()

        errorOnTimeout(testContext)
    }
}
