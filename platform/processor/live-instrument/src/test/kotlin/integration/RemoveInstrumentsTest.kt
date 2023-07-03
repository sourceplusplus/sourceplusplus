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
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.event.LiveInstrumentRemoved
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener
import java.util.concurrent.atomic.AtomicInteger

class RemoveInstrumentsTest : LiveInstrumentIntegrationTest() {

    @Test
    fun `remove multiple by location`() = runBlocking {
        val testContext = VertxTestContext()

        log.info("Adding instruments")
        val instruments = instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveInstrumentsTest::class.java.name,
                        service = Service.fromName("spp-test-probe")
                    ),
                    id = testNameAsUniqueInstrumentId
                ),
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        RemoveInstrumentsTest::class.java.name,
                        service = Service.fromName("spp-test-probe")
                    ),
                    id = testNameAsUniqueInstrumentId
                )
            )
        ).await()
        assertEquals(2, instruments.size)
        log.info("Added ${instruments.size} instruments")

        val removedCount = AtomicInteger()
        val listener = object : LiveInstrumentListener {
            override fun onInstrumentRemovedEvent(event: LiveInstrumentRemoved) {
                testContext.verify {
                    assertEquals(
                        LiveSourceLocation(
                            RemoveInstrumentsTest::class.java.name,
                            service = Service.fromName("spp-test-probe")
                        ),
                        event.instrument.location
                    )

                    if (removedCount.incrementAndGet() == 2) {
                        testContext.completeNow()
                    }
                }
            }
        }
        instruments.forEach {
            vertx.addLiveInstrumentListener(it.id!!, listener).await()
        }

        val removeInstruments = instrumentService.removeLiveInstruments(
            LiveSourceLocation(
                RemoveInstrumentsTest::class.java.name,
                service = Service.fromName("spp-test-probe")
            )
        ).await()
        assertEquals(2, removeInstruments.size)

        errorOnTimeout(testContext)
    }
}
