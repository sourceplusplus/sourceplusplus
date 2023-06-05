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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.addBreakpointHitListener

class SimplePrimitivesLiveInstrumentTest : LiveInstrumentIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun simplePrimitives() {
        startEntrySpan("simplePrimitives")
        val i = 1
        val c = 'h'
        val s = "hi"
        val f = 1.0f
        val max = Long.MAX_VALUE
        val b: Byte = -2
        val sh = Short.MIN_VALUE
        val d = 00.23
        val bool = true
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `primitive values`() = runBlocking {
        setupLineLabels {
            simplePrimitives()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { event ->
            testContext.verify {
                assertTrue(event.stackTrace.elements.isNotEmpty())
                val topFrame = event.stackTrace.elements.first()
                assertEquals(10, topFrame.variables.size)

                //byte
                assertEquals(-2, topFrame.variables.find { it.name == "b" }!!.value)
                assertEquals(
                    "java.lang.Byte",
                    topFrame.variables.find { it.name == "b" }!!.liveClazz
                )

                //char
                assertEquals("h", topFrame.variables.find { it.name == "c" }!!.value)
                assertEquals(
                    "java.lang.Character",
                    topFrame.variables.find { it.name == "c" }!!.liveClazz
                )

                //string
                assertEquals("hi", topFrame.variables.find { it.name == "s" }!!.value)
                assertEquals(
                    "java.lang.String",
                    topFrame.variables.find { it.name == "s" }!!.liveClazz
                )

                //double
                assertEquals(0.23, topFrame.variables.find { it.name == "d" }!!.value)
                assertEquals(
                    "java.lang.Double",
                    topFrame.variables.find { it.name == "d" }!!.liveClazz
                )

                //bool
                assertEquals(true, topFrame.variables.find { it.name == "bool" }!!.value)
                assertEquals(
                    "java.lang.Boolean",
                    topFrame.variables.find { it.name == "bool" }!!.liveClazz
                )

                //long
                assertEquals(Long.MAX_VALUE, topFrame.variables.find { it.name == "max" }!!.value)
                assertEquals(
                    "java.lang.Long",
                    topFrame.variables.find { it.name == "max" }!!.liveClazz
                )

                //short
                assertEquals(
                    Short.MIN_VALUE.toInt(),
                    topFrame.variables.find { it.name == "sh" }!!.value
                )
                assertEquals(
                    "java.lang.Short",
                    topFrame.variables.find { it.name == "sh" }!!.liveClazz
                )

                //float
                assertEquals(1.0, topFrame.variables.find { it.name == "f" }!!.value)
                assertEquals(
                    "java.lang.Float",
                    topFrame.variables.find { it.name == "f" }!!.liveClazz
                )

                //integer
                assertEquals(1, topFrame.variables.find { it.name == "i" }!!.value)
                assertEquals(
                    "java.lang.Integer",
                    topFrame.variables.find { it.name == "i" }!!.liveClazz
                )
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    SimplePrimitivesLiveInstrumentTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true,
                id = testNameAsInstrumentId
            )
        ).await()

        //trigger live breakpoint
        simplePrimitives()

        errorOnTimeout(testContext)
    }
}
