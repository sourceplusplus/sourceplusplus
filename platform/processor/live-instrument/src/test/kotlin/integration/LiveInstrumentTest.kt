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
package integration

import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.addBreakpointHitListener
import spp.protocol.service.listen.addLogHitListener
import java.util.concurrent.atomic.AtomicInteger

class LiveInstrumentTest : LiveInstrumentIntegrationTest() {

    private fun doTest() {
        startEntrySpan("doTest")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun getLiveInstrumentById_missing() {
        val testContext = VertxTestContext()
        instrumentService.getLiveInstrument("whatever").onComplete {
            if (it.succeeded()) {
                testContext.verify {
                    assertNull(it.result())
                }
                testContext.completeNow()
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun getLiveInstrumentById(): Unit = runBlocking {
        val instrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(location = LiveSourceLocation("integration.LiveInstrumentTest", 1))
        ).await()

        val originalId = instrument.id!!
        val getInstrument = instrumentService.getLiveInstrument(originalId).await()
        assertEquals(originalId, getInstrument!!.id!!)

        assertNotNull(instrumentService.removeLiveInstrument(originalId).await())
    }

    @Test
    fun getLiveInstrumentByIds(): Unit = runBlocking {
        val instrument = instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(location = LiveSourceLocation("integration.LiveInstrumentTest", 1)),
                LiveBreakpoint(location = LiveSourceLocation("integration.LiveInstrumentTest", 2))
            )
        ).await()

        val originalIds = instrument.map { it.id!! }
        val getInstrument = instrumentService.getLiveInstrumentsByIds(originalIds).await()
        assertEquals(2, getInstrument.size)
        assertEquals(2, originalIds.size)
        assertTrue(getInstrument[0].id!! in originalIds)
        assertTrue(getInstrument[1].id!! in originalIds)

        assertNotNull(instrumentService.removeLiveInstrument(originalIds[0]).await())
        assertNotNull(instrumentService.removeLiveInstrument(originalIds[1]).await())
    }

    @RepeatedTest(2)
    fun addLiveLogAndLiveBreakpoint(): Unit = runBlocking {
        setupLineLabels {
            doTest()
        }

        val hitCount = AtomicInteger()
        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-breakpoint") { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(1, topFrame.variables.size)
            }
            if (hitCount.incrementAndGet() == 2) {
                testContext.completeNow()
            }
        }.await()
        vertx.addLogHitListener("$testNameAsInstrumentId-log") {
            if (hitCount.incrementAndGet() == 2) {
                testContext.completeNow()
            }
        }.await()

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true,
                id = "$testNameAsInstrumentId-log"
            )
        ).await()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true,
                id = "$testNameAsInstrumentId-breakpoint"
            )
        ).await()

        doTest()

        errorOnTimeout(testContext)

        assertNull(instrumentService.removeLiveInstrument("$testNameAsInstrumentId-log").await())
        assertNull(instrumentService.removeLiveInstrument("$testNameAsInstrumentId-breakpoint").await())
    }

    @RepeatedTest(2, name = "addLiveLogAndLiveBreakpoint_noLogHit-{currentRepetition}-of-{totalRepetitions}")
    fun addLiveLogAndLiveBreakpoint_noLogHit(): Unit = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener("$testNameAsInstrumentId-breakpoint") { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(1, topFrame.variables.size)
            }
            testContext.completeNow()
        }.await()

        instrumentService.addLiveInstrument(
            LiveLog(
                "test {}",
                listOf("b"),
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                "1==2",
                applyImmediately = true,
                id = "$testNameAsInstrumentId-log"
            )
        ).await()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LiveInstrumentTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true,
                id = "$testNameAsInstrumentId-breakpoint"
            )
        ).await()

        doTest()

        errorOnTimeout(testContext)

        assertNotNull(instrumentService.removeLiveInstrument("$testNameAsInstrumentId-log").await())
        assertNull(instrumentService.removeLiveInstrument("$testNameAsInstrumentId-breakpoint").await())
    }
}
