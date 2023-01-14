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
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation

class MetaLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    @Test
    fun `live breakpoint meta`(): Unit = runBlocking {
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    "non-existent-class",
                    0,
                ),
                meta = mapOf("key" to "value"),
                id = testNameAsInstrumentId
            )
        ).await()
        assertNotNull(liveInstrument)
        assertEquals(mapOf("key" to "value"), liveInstrument.meta.filter { it.key == "key" })

        //verify internal meta is not exposed
        assertEquals(emptyMap<String, String>(), liveInstrument.meta.filter { it.key.startsWith("spp.") })

        //get stored instrument meta
        val storedMeta = instrumentService.getLiveInstrumentById(liveInstrument.id!!).await()
        assertNotNull(storedMeta)
        assertEquals(mapOf("key" to "value"), storedMeta!!.meta.filter { it.key == "key" })

        //verify internal meta is not exposed
        assertEquals(emptyMap<String, String>(), storedMeta.meta.filter { it.key.startsWith("spp.") })

        //remove instrument
        val removedInstrument = instrumentService.removeLiveInstrument(liveInstrument.id!!).await()
        assertNotNull(removedInstrument)
        assertEquals(mapOf("key" to "value"), removedInstrument!!.meta.filter { it.key == "key" })

        //verify internal meta is not exposed
        assertEquals(emptyMap<String, String>(), removedInstrument.meta.filter { it.key.startsWith("spp.") })
    }

    @Test
    fun `verify add live instruments meta`(): Unit = runBlocking {
        val location = LiveSourceLocation("non-existent-class-1")
        val liveInstruments = instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = location,
                    id = testNameAsInstrumentId
                )
            )
        ).await()
        assertEquals(1, liveInstruments.size)

        //verify internal meta is not exposed
        assertEquals(emptyMap<String, String>(), liveInstruments.first().meta.filter { it.key.startsWith("spp.") })

        //get instrument by ids
        val getInstruments = instrumentService.getLiveInstrumentsByIds(liveInstruments.mapNotNull { it.id }).await()
        assertEquals(1, getInstruments.size)
        assertEquals(emptyMap<String, String>(), getInstruments.first().meta.filter { it.key.startsWith("spp.") })

        //clean up
        instrumentService.removeLiveInstrument(liveInstruments.first().id!!).await()
    }

    @Test
    fun `verify get live instruments meta`(): Unit = runBlocking {
        val location = LiveSourceLocation("non-existent-class-2")
        val liveInstruments = instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = location,
                    id = testNameAsInstrumentId
                )
            )
        ).await()
        assertNotNull(liveInstruments)

        //get instruments
        val getInstruments = instrumentService.getLiveInstruments().await()
        assertEquals(1, getInstruments.size)
        assertEquals(emptyMap<String, String>(), getInstruments.first().meta.filter { it.key.startsWith("spp.") })

        //get instrument by id
        val getInstrument = instrumentService.getLiveInstrumentById(liveInstruments.first().id!!).await()
        assertNotNull(getInstrument)
        assertEquals(emptyMap<String, String>(), getInstrument!!.meta.filter { it.key.startsWith("spp.") })

        //clean
        instrumentService.removeLiveInstrument(liveInstruments.first().id!!).await()
    }

    @Test
    fun `verify remove live instruments by location meta`() = runBlocking {
        val location = LiveSourceLocation("non-existent-class-3")
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = location,
                id = testNameAsInstrumentId
            )
        ).await()
        assertNotNull(liveInstrument)

        //remove instrument
        val removedInstruments = instrumentService.removeLiveInstruments(location).await()
        assertEquals(1, removedInstruments.size)
        assertEquals(emptyMap<String, String>(), removedInstruments.first().meta.filter { it.key.startsWith("spp.") })
    }

    @Test
    fun `verify applied at meta update`(): Unit = runBlocking {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }

        val location = LiveSourceLocation(
            MetaLiveBreakpointTest::class.qualifiedName!!,
            getLineNumber("done"),
            "spp-test-probe"
        )
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = location,
                applyImmediately = true,
                id = testNameAsInstrumentId
            )
        ).await()
        assertNotNull(liveInstrument)

        //verify applied_at meta is set
        val getInstrument = instrumentService.getLiveInstrumentById(liveInstrument.id!!).await()
        assertNotNull(getInstrument)
        assertEquals(liveInstrument.meta["applied_at"], getInstrument!!.meta["applied_at"])

        //clean
        instrumentService.removeLiveInstrument(liveInstrument.id!!).await()
    }
}
