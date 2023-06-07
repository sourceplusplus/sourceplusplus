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
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.addBreakpointHitListener

class SmallMapLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun smallMapNullValue() {
        startEntrySpan("smallMapNullValue")
        val smallMap = LinkedHashMap<Int, String?>()
        for (i in 0 until 10) {
            smallMap[i] = null
        }
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    private fun smallMapIntKey() {
        startEntrySpan("smallMapIntKey")
        val smallMap = LinkedHashMap<Int, String>()
        for (i in 0 until 10) {
            smallMap[i] = i.toString()
        }
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    private fun smallMapStringKey() {
        startEntrySpan("smallMapStringKey")
        val smallMap = LinkedHashMap<String, Int>()
        for (i in 0 until 10) {
            smallMap[i.toString()] = i
        }
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `small map with null value`() = runBlocking {
        setupLineLabels {
            smallMapNullValue()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //smallMap
                val smallMapVariable = topFrame.variables.first { it.name == "smallMap" }
                assertNotNull(smallMapVariable)
                assertEquals(
                    "java.util.LinkedHashMap",
                    smallMapVariable.liveClazz
                )
                assertNotNull(smallMapVariable.liveIdentity)

                val mapValues = smallMapVariable.value as JsonArray
                assertEquals(10, mapValues.size())
                for (i in 0 until 10) {
                    val map = mapValues.getJsonObject(i)
                    assertEquals(7, map.size())
                    assertEquals(i.toString(), map.getString("name"))
                    assertNull(map.getValue("value"))
                }
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    SmallMapLiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        smallMapNullValue()

        errorOnTimeout(testContext)
    }

    @Test
    @Disabled("Non-string keys are not supported") //todo: fix this
    fun `small map with int key`() = runBlocking {
        setupLineLabels {
            smallMapIntKey()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //smallMap
                val smallMapVariable = topFrame.variables.first { it.name == "smallMap" }
                assertNotNull(smallMapVariable)
                assertEquals(
                    "java.util.LinkedHashMap",
                    smallMapVariable.liveClazz
                )

                val mapValues = smallMapVariable.value as JsonArray
                assertEquals(10, mapValues.size())
                for (i in 0 until 10) {
                    val map = mapValues.getJsonObject(i)
                    assertEquals(7, map.size())
                    assertEquals(i.toString(), map.getString("name"))
                    assertEquals(i, map.getInteger("value"))
                }
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    SmallMapLiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        smallMapIntKey()

        errorOnTimeout(testContext)
    }

    @Test
    fun `small map with string key`() = runBlocking {
        setupLineLabels {
            smallMapStringKey()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //smallMap
                val smallMapVariable = topFrame.variables.first { it.name == "smallMap" }
                assertNotNull(smallMapVariable)
                assertEquals(
                    "java.util.LinkedHashMap",
                    smallMapVariable.liveClazz
                )
                assertNotNull(smallMapVariable.liveIdentity)

                val mapValues = smallMapVariable.value as JsonArray
                assertEquals(10, mapValues.size())
                for (i in 0 until 10) {
                    val map = mapValues.getJsonObject(i)
                    assertEquals(7, map.size())
                    assertEquals(i.toString(), map.getString("name"))
                    assertEquals(i, map.getInteger("value"))
                }
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    SmallMapLiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        smallMapStringKey()

        errorOnTimeout(testContext)
    }
}
