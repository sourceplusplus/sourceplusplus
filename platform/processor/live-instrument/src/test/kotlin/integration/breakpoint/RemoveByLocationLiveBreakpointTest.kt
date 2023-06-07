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
import io.vertx.core.Promise
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentApplied
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addBreakpointHitListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.atomic.AtomicInteger

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

        val instrumentId = testNameAsUniqueInstrumentId
        val hitCount = AtomicInteger(0)
        val testContext = VertxTestContext()
        val listener: (LiveBreakpointHit) -> Unit = { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()

                if (topFrame.variables.find { it.name == "line2Var" } != null) {
                    if (hitCount.incrementAndGet() == 2) {
                        testContext.completeNow()
                    }
                } else {
                    assertNotNull(topFrame.variables.find { it.name == "line1Var" })
                    if (hitCount.incrementAndGet() == 2) {
                        testContext.completeNow()
                    }
                }
            }
        }
        vertx.addBreakpointHitListener("$instrumentId-1", listener).await()
        vertx.addBreakpointHitListener("$instrumentId-2", listener).await()

        val applyCount = AtomicInteger(0)
        val instrumentsApplied = Promise.promise<Nothing>()
        val appliedListener = object : LiveInstrumentListener {
            override fun onInstrumentAppliedEvent(event: LiveInstrumentApplied) {
                if (applyCount.incrementAndGet() == 2) {
                    instrumentsApplied.complete()
                }
            }
        }
        vertx.addLiveInstrumentListener("$instrumentId-1", appliedListener).await()
        vertx.addLiveInstrumentListener("$instrumentId-2", appliedListener).await()

        //add live breakpoints
        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveByLocationLiveBreakpointTest::class.java.name,
                        getLineNumber("line1"),
                        "spp-test-probe"
                    ),
                    hitLimit = 2,
                    //applyImmediately = true,
                    id = "$instrumentId-1"
                ),
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveByLocationLiveBreakpointTest::class.java.name,
                        getLineNumber("line2"),
                        "spp-test-probe"
                    ),
                    hitLimit = 2,
                    //applyImmediately = true,
                    id = "$instrumentId-2"
                )
            )
        ).await()

        //todo: wait since applyImmediately doesn't work on multi adds
        instrumentsApplied.future().await()

        //trigger live breakpoint
        removeMultipleByLine()

        errorOnTimeout(testContext)

        //remove line1 breakpoint by line number
        val removedInstruments = instrumentService.removeLiveInstruments(
            LiveSourceLocation(
                RemoveByLocationLiveBreakpointTest::class.java.name,
                getLineNumber("line1"),
                "spp-test-probe"
            )
        ).await()
        testContext.verify {
            assertEquals(1, removedInstruments.size)
            assertEquals(getLineNumber("line1"), removedInstruments.first().location.line)
        }

        //ensure line1 is removed and line2 is still there
        val remainingInstruments = instrumentService.getLiveInstrumentsByLocation(
            LiveSourceLocation(RemoveByLocationLiveBreakpointTest::class.java.name, service = "spp-test-probe")
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
        assertEquals(
            1,
            instrumentService.removeLiveInstruments(
                LiveSourceLocation(RemoveByLocationLiveBreakpointTest::class.java.name, service = "spp-test-probe")
            ).await().size
        )
    }
}
