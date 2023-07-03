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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import spp.protocol.artifact.exception.sourceAsLineNumber
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.listen.addBreakpointHitListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MultiLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun multiLineTest() {
        startEntrySpan("multiLineTest")
        addLineLabel("line1") { Throwable().stackTrace[0].lineNumber }
        addLineLabel("line2") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `same line twice`() = runBlocking {
        setupLineLabels {
            multiLineTest()
        }

        val gotAllHitsLatch = CountDownLatch(2)
        val testContext = VertxTestContext()
        val listener: (LiveBreakpointHit) -> Unit = { bpHit ->
            log.info("Hit: $bpHit")
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()

                if (topFrame.sourceAsLineNumber() == getLineNumber("line1")) {
                    gotAllHitsLatch.countDown()
                } else {
                    fail("Unexpected line number: ${topFrame.sourceAsLineNumber()}")
                }
            }
        }
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-1", listener).await()
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-2", listener).await()

        //add live breakpoints
        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        MultiLiveBreakpointTest::class.java.name,
                        getLineNumber("line1"),
                        Service.fromName("spp-test-probe")
                    ),
                    applyImmediately = true,
                    id = "$testNameAsInstrumentId-1"
                ),
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        MultiLiveBreakpointTest::class.java.name,
                        getLineNumber("line1"),
                        Service.fromName("spp-test-probe")
                    ),
                    applyImmediately = true,
                    id = "$testNameAsInstrumentId-2"
                )
            )
        ).await()

        //trigger live breakpoints
        multiLineTest()

        if (!gotAllHitsLatch.await(30, TimeUnit.SECONDS)) {
            testContext.failNow(RuntimeException("didn't get all hits"))
        }
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }

    @Test
    fun `side by side`() = runBlocking {
        setupLineLabels {
            multiLineTest()
        }

        val gotLine1Promise = Promise.promise<Void>()
        val gotLine2Promise = Promise.promise<Void>()
        val testContext = VertxTestContext()
        val listener: (LiveBreakpointHit) -> Unit = { bpHit ->
            log.info("Hit: $bpHit")
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()

                if (topFrame.sourceAsLineNumber() == getLineNumber("line1")) {
                    if (gotLine1Promise.future().isComplete) {
                        gotLine1Promise.fail("got line1 twice")
                    } else {
                        gotLine1Promise.complete()
                    }
                } else if (topFrame.sourceAsLineNumber() == getLineNumber("line2")) {
                    if (gotLine2Promise.future().isComplete) {
                        gotLine2Promise.fail("got line2 twice")
                    } else {
                        gotLine2Promise.complete()
                    }
                } else {
                    fail("Unexpected line number: ${topFrame.sourceAsLineNumber()}")
                }
            }
        }
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-1", listener).await()
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-2", listener).await()

        //add live breakpoints
        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        MultiLiveBreakpointTest::class.java.name,
                        getLineNumber("line1"),
                        Service.fromName("spp-test-probe")
                    ),
                    applyImmediately = true,
                    id = "$testNameAsInstrumentId-1"
                ),
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        MultiLiveBreakpointTest::class.java.name,
                        getLineNumber("line2"),
                        Service.fromName("spp-test-probe")
                    ),
                    applyImmediately = true,
                    id = "$testNameAsInstrumentId-2"
                )
            )
        ).await()

        //trigger live breakpoints
        multiLineTest()

        gotLine1Promise.future().await()
        gotLine2Promise.future().await()

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }
}
