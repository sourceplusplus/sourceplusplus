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

class CyclicObjectLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    class TopObject {
        var bottom: BottomObject? = null
    }

    class BottomObject {
        var top: TopObject? = null
    }

    private fun cyclicObject() {
        startEntrySpan("cyclicObject")
        val cyclicObject = TopObject()
        cyclicObject.bottom = BottomObject()
        cyclicObject.bottom!!.top = cyclicObject
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `cyclic object`() = runBlocking {
        setupLineLabels {
            cyclicObject()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //cyclicObject
                val cyclicObject = topFrame.variables.first { it.name == "cyclicObject" }
                assertEquals(
                    TopObject::class.java.name,
                    cyclicObject.liveClazz
                )
                val cyclicObjectId = cyclicObject.liveIdentity
                assertNotNull(cyclicObjectId)

                val bottomObject = (cyclicObject.value as JsonArray).first() as JsonObject
                assertEquals(
                    BottomObject::class.java.name,
                    bottomObject.getString("liveClazz")
                )

                val topObject = (bottomObject.getJsonArray("value")).first() as JsonObject
                assertNotNull(topObject)
                assertEquals(
                    TopObject::class.java.name,
                    topObject.getString("liveClazz")
                )

                val topObjectId = topObject.getString("liveIdentity")
                assertNotNull(topObjectId)
                assertEquals(cyclicObjectId, topObjectId)
            }

            //test passed
            testContext.completeNow()
        }.completionHandler().await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    CyclicObjectLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        cyclicObject()

        errorOnTimeout(testContext)
    }
}
