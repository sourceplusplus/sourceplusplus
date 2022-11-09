/*
 * Source++, the continuous feedback platform for developers.
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
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.atomic.AtomicInteger

class RemoveInstrumentsTest : LiveInstrumentIntegrationTest() {

    @Test
    fun `remove multiple by location`() = runBlocking {
        val testContext = VertxTestContext()

        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveInstrumentsTest::class.qualifiedName!!,
                        1,
                    )
                ),
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveInstrumentsTest::class.qualifiedName!!,
                        1,
                    )
                )
            )
        ).await()

        val removedCount = AtomicInteger()
        vertx.addLiveInstrumentListener("system", object : LiveInstrumentListener {
            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                testContext.verify {
                    assertEquals(
                        LiveSourceLocation(RemoveInstrumentsTest::class.qualifiedName!!, 1),
                        event.liveInstrument.location
                    )

                    if (removedCount.incrementAndGet() == 2) {
                        testContext.completeNow()
                    }
                }
            }
        }).await()

        instrumentService.removeLiveInstruments(
            LiveSourceLocation(
                RemoveInstrumentsTest::class.qualifiedName!!,
                1,
            )
        ).await()

        errorOnTimeout(testContext)
    }
}
