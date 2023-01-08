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
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation

@Suppress("UNUSED_VARIABLE")
class LargeObjectLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun largeObject() {
        startEntrySpan("deepObject")
        val twoMbArr = ByteArray(1024 * 1024 * 2)
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `max size exceeded`() = runBlocking {
        setupLineLabels {
            largeObject()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //max size exceeded
                val twoMbArrVariable = topFrame.variables.first { it.name == "twoMbArr" }
                val twoMbArrVariableData = twoMbArrVariable.value as JsonObject
                assertEquals(
                    "MAX_SIZE_EXCEEDED",
                    twoMbArrVariableData.getString("@skip")
                )
                assertEquals(
                    "[B",
                    twoMbArrVariableData.getString("@class")
                )
                assertEquals(
                    (1024 * 1024 * 2) + 16, //2mb + 16 bytes for byte[] size
                    twoMbArrVariableData.getString("@skip[size]").toInt()
                )
                assertEquals(
                    1024 * 1024, //1mb
                    twoMbArrVariableData.getString("@skip[max]").toInt()
                )
            }

            //test passed
            testContext.completeNow()
        }.completionHandler().await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LargeObjectLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        largeObject()

        errorOnTimeout(testContext)
    }
}
