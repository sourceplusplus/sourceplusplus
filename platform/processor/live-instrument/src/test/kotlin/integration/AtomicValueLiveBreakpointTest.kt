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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.variable.LiveVariable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("unused", "UNUSED_VARIABLE")
class AtomicValueLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun atomicValue() {
        startEntrySpan("atomicValue")
        val atomicMap = AtomicReference(mapOf("test" to "test"))
        val atomicString = AtomicReference<String>().apply { set("test") }
        val atomicInteger = AtomicInteger(1)
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `atomic value`() {
        setupLineLabels {
            atomicValue()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(4, topFrame.variables.size)

                //atomicMap
                val atomicMapVariable = topFrame.variables.first { it.name == "atomicMap" }
                assertNotNull(atomicMapVariable)
                assertEquals(
                    "java.util.concurrent.atomic.AtomicReference",
                    atomicMapVariable.liveClazz
                )
                assertNotNull(atomicMapVariable.liveIdentity)
                val atomicMapValue = LiveVariable(
                    JsonObject.mapFrom((atomicMapVariable.value as JsonArray).first())
                )
                assertNotNull(atomicMapValue)
                assertEquals(
                    "java.util.Collections\$SingletonMap",
                    atomicMapValue.liveClazz
                )
                val atomicMapFinalValue = LiveVariable(
                    JsonObject.mapFrom((atomicMapValue.value as JsonArray).first())
                )
                assertNotNull(atomicMapFinalValue)
                assertEquals("test", atomicMapFinalValue.name)
                assertEquals("test", atomicMapFinalValue.value)

                //atomicString
                val atomicStringVariable = topFrame.variables.first { it.name == "atomicString" }
                assertNotNull(atomicStringVariable)
                assertEquals(
                    "java.util.concurrent.atomic.AtomicReference",
                    atomicStringVariable.liveClazz
                )
                assertNotNull(atomicStringVariable.liveIdentity)
                val atomicStringValue = LiveVariable(
                    JsonObject.mapFrom((atomicStringVariable.value as JsonArray).first())
                )
                assertNotNull(atomicStringValue)
                assertEquals("test", atomicStringValue.value)

                //atomicInteger
                val atomicIntegerVariable = topFrame.variables.first { it.name == "atomicInteger" }
                assertNotNull(atomicIntegerVariable)
                assertEquals(
                    "java.util.concurrent.atomic.AtomicInteger",
                    atomicIntegerVariable.liveClazz
                )
                assertNotNull(atomicIntegerVariable.liveIdentity)
                assertEquals(1, atomicIntegerVariable.value)
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
                        AtomicValueLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    atomicValue()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
