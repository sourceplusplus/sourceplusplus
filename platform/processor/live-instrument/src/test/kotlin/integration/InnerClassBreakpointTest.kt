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
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.variable.LiveVariableScope

@Suppress("unused", "UNUSED_VARIABLE")
class InnerClassBreakpointTest : LiveInstrumentIntegrationTest() {

    inner class InnerClass {
        fun doHit() {
            startEntrySpan("largeList")
            val myVar = 10
            addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
            stopSpan()
        }
    }

    @Test
    fun `inner class`() {
        setupLineLabels {
            InnerClass().doHit()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(3, topFrame.variables.size)

                val myVar = topFrame.variables.first { it.name == "myVar" }
                assertEquals("myVar", myVar.name)
                assertEquals(10, myVar.value)
                assertEquals("java.lang.Integer", myVar.liveClazz)
                assertEquals(LiveVariableScope.LOCAL_VARIABLE, myVar.scope)
                assertNotNull(myVar.liveIdentity)
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
                        InnerClass::class.java.name,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    InnerClass().doHit()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
