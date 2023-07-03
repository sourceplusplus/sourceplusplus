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
import spp.protocol.instrument.variable.LiveVariable
import spp.protocol.platform.general.Service
import spp.protocol.service.listen.addBreakpointHitListener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("UNUSED_VARIABLE")
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
    fun `atomic values`() = runBlocking {
        setupLineLabels {
            atomicValue()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
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
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    AtomicValueLiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    Service.fromName("spp-test-probe")
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        atomicValue()

        errorOnTimeout(testContext)
    }
}
