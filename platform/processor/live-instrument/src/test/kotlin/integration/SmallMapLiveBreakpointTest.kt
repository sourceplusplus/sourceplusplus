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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("unused")
class SmallMapLiveBreakpointTest : LiveInstrumentIntegrationTest() {

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
    @Disabled("Non-string keys are not supported") //todo: fix this
    fun `small map with int key`() {
        setupLineLabels {
            smallMapStringKey()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
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

                val mapValues = smallMapVariable.value as List<Map<String, Any>>
                assertEquals(10, mapValues.size)
                for (i in 0 until 10) {
                    val map = mapValues[i]
                    assertEquals(7, map.size)
                    assertEquals(i.toString(), map["name"])
                    assertEquals(i, map["value"])
                }
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
                        SmallMapLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    smallMapStringKey()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun `small map with string key`() {
        setupLineLabels {
            smallMapStringKey()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
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

                val mapValues = smallMapVariable.value as List<Map<String, Any>>
                assertEquals(10, mapValues.size)
                for (i in 0 until 10) {
                    val map = mapValues[i]
                    assertEquals(7, map.size)
                    assertEquals(i.toString(), map["name"])
                    assertEquals(i, map["value"])
                }
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
                        SmallMapLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    smallMapStringKey()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
