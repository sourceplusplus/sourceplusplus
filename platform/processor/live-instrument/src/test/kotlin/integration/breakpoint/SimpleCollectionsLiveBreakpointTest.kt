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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.listen.addBreakpointHitListener

@Suppress("UNUSED_VARIABLE")
class SimpleCollectionsLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun simpleCollections() {
        startEntrySpan("simpleCollections")
        val emptyList = emptyList<String>()
        val byteArr = byteArrayOf(1, 2, 3)
        val intArr = intArrayOf(1, 2, 3)
        val stringSet = setOf("a", "b", "c")
        val doubleMap = mapOf(1.0 to 1.1, 2.0 to 2.1, 3.0 to 3.1)
        val arrOfArrays = arrayOf(intArrayOf(1, 2, 3), intArrayOf(4, 5, 6))
        val mapOfMaps = mapOf(
            "a" to mapOf("a" to 1, "b" to 2, "c" to 3),
            "b" to mapOf("a" to 4, "b" to 5, "c" to 6)
        )
        val listOfLists = listOf(
            listOf(1, 2, 3),
            listOf(4, 5, 6)
        )
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `primitive collections`() = runBlocking {
        setupLineLabels {
            simpleCollections()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(9, topFrame.variables.size)

                //emptyList
                assertEquals(JsonArray(), topFrame.variables.find { it.name == "emptyList" }!!.value)
                assertEquals(
                    "kotlin.collections.EmptyList",
                    topFrame.variables.find { it.name == "emptyList" }!!.liveClazz
                )

                //byteArr
                assertEquals(
                    listOf(1, 2, 3),
                    topFrame.variables.find { it.name == "byteArr" }!!.value.let {
                        (it as JsonArray).map { JsonObject.mapFrom(it) }.map { it.getInteger("value") }
                    }
                )
                assertEquals(
                    "byte[]",
                    topFrame.variables.find { it.name == "byteArr" }!!.liveClazz
                )

                //intArr
                assertEquals(
                    listOf(1, 2, 3),
                    topFrame.variables.find { it.name == "intArr" }!!.value.let {
                        (it as JsonArray).map { JsonObject.mapFrom(it) }.map { it.getInteger("value") }
                    }
                )
                assertEquals(
                    "int[]",
                    topFrame.variables.find { it.name == "intArr" }!!.liveClazz
                )

                //stringSet
                assertEquals(
                    listOf("a", "b", "c"),
                    topFrame.variables.find { it.name == "stringSet" }!!.value.let {
                        (it as JsonArray).map { JsonObject.mapFrom(it) }.map { it.getString("value") }
                    }
                )
                assertEquals(
                    "java.util.LinkedHashSet",
                    topFrame.variables.find { it.name == "stringSet" }!!.liveClazz
                )

                //doubleMap
                assertEquals(
                    listOf(1.0 to 1.1, 2.0 to 2.1, 3.0 to 3.1),
                    topFrame.variables.find { it.name == "doubleMap" }!!.value.let {
                        (it as JsonArray).map { JsonObject.mapFrom(it) }
                            .map { it.getString("name").toDouble() to it.getDouble("value") }
                    }
                )
                assertEquals(
                    "java.util.LinkedHashMap",
                    topFrame.variables.find { it.name == "doubleMap" }!!.liveClazz
                )

                //todo: throws MAX_DEPTH_EXCEEDED
//                //arrOfArrays
//                assertEquals(
//                    listOf(
//                        listOf(1, 2, 3),
//                        listOf(4, 5, 6)
//                    ),
//                    topFrame.variables.find { it.name == "arrOfArrays" }!!.value.let {
//                        (it as List<Map<String, *>>).map { it["value"] }
//                    }
//                )
//                assertEquals(
//                    "int[][]",
//                    topFrame.variables.find { it.name == "arrOfArrays" }!!.liveClazz
//                )

                //todo: returns invalid map
//                //mapOfMaps
//                assertEquals(
//                    listOf(
//                        "a" to mapOf("a" to 1, "b" to 2, "c" to 3),
//                        "b" to mapOf("a" to 4, "b" to 5, "c" to 6)
//                    ),
//                    topFrame.variables.find { it.name == "mapOfMaps" }!!.value.let {
//                        (it as List<Map<String, *>>).map {
//                            it["name"] to (it["value"] as List<Map<String, *>>).map { it["name"] to it["value"] }
//                        }
//                    }
//                )
                assertEquals(
                    "java.util.LinkedHashMap",
                    topFrame.variables.find { it.name == "mapOfMaps" }!!.liveClazz
                )

                //todo: throws MAX_DEPTH_EXCEEDED
//                //listOfLists
//                assertEquals(
//                    listOf(
//                        listOf(1, 2, 3),
//                        listOf(4, 5, 6)
//                    ),
//                    topFrame.variables.find { it.name == "listOfLists" }!!.value.let {
//                        (it as List<Map<String, *>>).map { it["value"] }
//                    }
//                )
//                assertEquals(
//                    "java.util.LinkedList",
//                    topFrame.variables.find { it.name == "listOfLists" }!!.liveClazz
//                )
            }

            //test passed
            testContext.completeNow()
        }.await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    SimpleCollectionsLiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    Service.fromName("spp-test-probe")
                ),
                applyImmediately = true,
                id = testNameAsInstrumentId
            )
        ).await()

        //trigger live breakpoint
        simpleCollections()

        errorOnTimeout(testContext)
    }
}
