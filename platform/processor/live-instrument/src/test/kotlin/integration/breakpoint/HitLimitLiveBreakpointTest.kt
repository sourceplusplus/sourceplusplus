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
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.throttle.InstrumentThrottle
import spp.protocol.platform.general.Service
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addBreakpointHitListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.atomic.AtomicInteger

class HitLimitLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun hitLimit() {
        startEntrySpan("hitLimit")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `11 hit limit`() = runBlocking {
        setupLineLabels {
            hitLimit()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    HitLimitLiveBreakpointTest::class.java.name,
                    getLineNumber("done"),
                    Service.fromName("spp-test-probe")
                ),
                hitLimit = 11,
                throttle = InstrumentThrottle.NONE,
                applyImmediately = true,
                id = testNameAsInstrumentId
            )
        ).await()

        //trigger live breakpoint 10 times
        val hitCount = AtomicInteger(0)
        val tenHitPromise = Promise.promise<Nothing>()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) {
            if (hitCount.incrementAndGet() == 10) {
                tenHitPromise.complete()
            }
        }.await()
        repeat(10) { hitLimit() }
        tenHitPromise.future().await()

        //verify still exists
        val liveInstrument = instrumentService.getLiveInstrument(testNameAsInstrumentId).await()
        assertEquals(11, liveInstrument!!.hitLimit)
//        assertEquals(10, liveInstrument.meta["hit_count"]) //todo: count hits via event history

        //trigger once more
        val removePromise = Promise.promise<Nothing>()
        vertx.addLiveInstrumentListener(testNameAsInstrumentId, object : LiveInstrumentListener {
            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                removePromise.complete()
            }
        }).await()
        hitLimit()
        removePromise.future().await()

        //verify removed
        assertNull(instrumentService.getLiveInstrument(testNameAsInstrumentId).await())
    }
}
