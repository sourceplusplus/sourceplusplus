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
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveInstrumentType
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.addBreakpointHitListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoveByLocationLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun removeMultipleByLine() {
        startEntrySpan("removeMultipleByLine")
        val line1Var = 1
        addLineLabel("line1") { Throwable().stackTrace[0].lineNumber }
        val line2Var = 2
        addLineLabel("line2") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `remove multiple by line number`() = runBlocking {
        setupLineLabels {
            removeMultipleByLine()
        }

        val gotAllHitsLatch = CountDownLatch(2)
        val testContext = VertxTestContext()
        val listener: (LiveBreakpointHit) -> Unit = { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()

                if (topFrame.variables.find { it.name == "line2Var" } != null) {
                    gotAllHitsLatch.countDown()
                } else {
                    assertNotNull(topFrame.variables.find { it.name == "line1Var" })
                    gotAllHitsLatch.countDown()
                }
            }
        }
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-1", listener).await()
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-2", listener).await()

        //add live breakpoint
        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveByLocationLiveBreakpointTest::class.java.name,
                        getLineNumber("line1"),
                        //"spp-test-probe" //todo: should be able to use with remove by location
                    ),
                    hitLimit = 2,
                    //applyImmediately = true,
                    id = "$testNameAsInstrumentId-1"
                ),
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveByLocationLiveBreakpointTest::class.java.name,
                        getLineNumber("line2"),
                        //"spp-test-probe" //todo: should be able to use with remove by location
                    ),
                    hitLimit = 2,
                    //applyImmediately = true,
                    id = "$testNameAsInstrumentId-2"
                )
            )
        ).await()

        //trigger live breakpoint
        vertx.setTimer(5000) { //todo: wait since applyImmediately doesn't work on multi adds
            removeMultipleByLine()
        }

        if (!gotAllHitsLatch.await(30, TimeUnit.SECONDS)) {
            testContext.failNow(RuntimeException("didn't get all hits"))
        }
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        //remove line1 breakpoint by line number
        val removedInstruments = instrumentService.removeLiveInstruments(
            LiveSourceLocation(
                RemoveByLocationLiveBreakpointTest::class.java.name,
                getLineNumber("line1"),
                //"spp-test-probe" //todo: doesn't work even if above uses
            )
        ).await()
        testContext.verify {
            assertEquals(1, removedInstruments.size)
            assertEquals(getLineNumber("line1"), removedInstruments.first().location.line)
        }

        //ensure line1 is removed and line2 is still there
        val remainingInstruments = instrumentService.getLiveInstrumentsByLocation(
            LiveSourceLocation(RemoveByLocationLiveBreakpointTest::class.java.name)
        ).await()
        testContext.verify {
            assertEquals(1, remainingInstruments.size)

            val line2Breakpoint = remainingInstruments.first()
            assertTrue(line2Breakpoint.location.line == getLineNumber("line2"))
        }

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        //clean up
        assertTrue(instrumentService.clearLiveInstruments(LiveInstrumentType.BREAKPOINT).await())
    }
}
