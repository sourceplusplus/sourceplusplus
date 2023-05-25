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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.*
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.atomic.AtomicInteger

class LiveInstrumentEventsTest : LiveInstrumentIntegrationTest() {

    private fun doTest() {
        startEntrySpan("doTest")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `get live instrument events`(): Unit = runBlocking {
        setupLineLabels {
            doTest()
        }

        val instrumentId = testNameAsUniqueInstrumentId
        val hitCount = AtomicInteger()
        val testContext = VertxTestContext()
        vertx.addLiveInstrumentListener(instrumentId, object : LiveInstrumentListener {
            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                if (hitCount.incrementAndGet() == 2) {
                    testContext.completeNow()
                }
            }

            override fun onInstrumentHitEvent(event: LiveInstrumentHit) {
                if (hitCount.incrementAndGet() == 2) {
                    testContext.completeNow()
                }
            }
        }).await()

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LiveInstrumentEventsTest::class.java.name,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true,
                id = instrumentId
            )
        ).await()

        doTest()
        errorOnTimeout(testContext)

        val events = instrumentService.getLiveInstrumentEvents(instrumentId).await()
        assertEquals(4, events.size)
        assertTrue(events.any { it is LiveBreakpointHit })
        assertTrue(events.any { it is LiveInstrumentRemoved })
        assertTrue(events.any { it is LiveInstrumentApplied })
        assertTrue(events.any { it is LiveInstrumentAdded })
    }
}
