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

import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("unused")
class MetaLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    @Test
    fun `live breakpoint meta`() = runBlocking {
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    "non-existent-class",
                    0,
                ),
                meta = mapOf(
                    "key" to "value",
                ),
            )
        ).await()
        assertNotNull(liveInstrument)
        assertEquals(mapOf("key" to "value"), liveInstrument.meta.filter { it.key == "key" })

        //verify meta
        val storedMeta = instrumentService.getLiveInstrumentById(liveInstrument.id!!).await()
        assertNotNull(storedMeta)
        assertEquals(mapOf("key" to "value"), storedMeta!!.meta.filter { it.key == "key" })

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveInstrument.id!!).await())
    }
}
