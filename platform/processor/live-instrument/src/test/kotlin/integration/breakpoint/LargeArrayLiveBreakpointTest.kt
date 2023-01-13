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
package integration.breakpoint

import integration.LiveInstrumentIntegrationTest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.addBreakpointHitListener
import java.util.*

class LargeArrayLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun largeArray() {
        startEntrySpan("largeArray")
        val largeArray = arrayOfNulls<Byte>(100_000)
        Arrays.fill(largeArray, 1.toByte())
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `large array`() = runBlocking {
        setupLineLabels {
            largeArray()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //largeArray
                val largeArrayVariable = topFrame.variables.first { it.name == "largeArray" }
                assertNotNull(largeArrayVariable)
                assertEquals(
                    "java.lang.Byte[]",
                    largeArrayVariable.liveClazz
                )

                val arrayValues = largeArrayVariable.value as JsonArray
                assertEquals(101, arrayValues.size())
                for (i in 0..99) {
                    val value = arrayValues.getJsonObject(i)
                    assertEquals(1, value.getInteger("value"))
                }
                val lastValue = (arrayValues.last() as JsonObject).getJsonObject("value")
                assertEquals("MAX_LENGTH_EXCEEDED", lastValue.getString("@skip"))
                assertEquals(100_000, lastValue.getInteger("@skip[size]"))
                assertEquals(100, lastValue.getInteger("@skip[max]"))
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    LargeArrayLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        largeArray()

        errorOnTimeout(testContext)
    }
}
