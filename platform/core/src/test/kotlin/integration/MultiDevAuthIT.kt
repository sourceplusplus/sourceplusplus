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

import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RolePermission
import spp.protocol.service.LiveInstrumentService
import java.util.*

@ExtendWith(VertxExtension::class)
class MultiDevAuthIT : PlatformIntegrationTest() {

    @Test
    fun `test clear self instruments`() = runBlocking {
        instrumentService.clearAllLiveInstruments().await()

        val uuid = UUID.randomUUID().toString()
        val devRole = DeveloperRole.fromString("get-self-instruments-$uuid")
        managementService.addRole(devRole).await()
        managementService.addRolePermission(devRole, RolePermission.ADD_LIVE_BREAKPOINT).await()
        managementService.addRolePermission(devRole, RolePermission.GET_LIVE_INSTRUMENTS).await()
        managementService.addRolePermission(devRole, RolePermission.REMOVE_LIVE_INSTRUMENT).await()

        val dev1 = managementService.addDeveloper("dev1-get-self-instruments-$uuid").await()
        managementService.addDeveloperRole(dev1.id, devRole).await()

        val dev2 = managementService.addDeveloper("dev2-get-self-instruments-$uuid").await()
        managementService.addDeveloperRole(dev2.id, devRole).await()

        val dev1AuthToken = managementService.getAuthToken(dev1.accessToken!!).await()
        val dev1InstrumentService = LiveInstrumentService.createProxy(vertx, dev1AuthToken)
        val dev1Instrument = dev1InstrumentService.addLiveBreakpoint(
            LiveBreakpoint(
                location = LiveSourceLocation("integration.InstrumentAuthTest", 1),
                condition = "1 == 2"
            )
        ).await()

        assertEquals(1, dev1InstrumentService.getLiveInstruments().await().size)

        val dev2AuthToken = managementService.getAuthToken(dev2.accessToken!!).await()
        val dev2InstrumentService = LiveInstrumentService.createProxy(vertx, dev2AuthToken)
        val dev2Instrument = dev2InstrumentService.addLiveBreakpoint(
            LiveBreakpoint(
                location = LiveSourceLocation("integration.InstrumentAuthTest", 1),
                condition = "1 == 2"
            )
        ).await()

        assertEquals(2, dev2InstrumentService.getLiveInstruments().await().size)

        //clear dev1's instruments
        dev1InstrumentService.clearLiveInstruments().await()
        assertNull(dev1InstrumentService.getLiveInstrumentById(dev1Instrument.id!!).await())
        assertNotNull(dev1InstrumentService.getLiveInstrumentById(dev2Instrument.id!!).await())
        assertNotNull(dev2InstrumentService.getLiveInstrumentById(dev2Instrument.id!!).await())

        //dev2's instruments should still be there
        assertEquals(1, dev1InstrumentService.getLiveInstruments().await().size)
        assertEquals(1, dev2InstrumentService.getLiveInstruments().await().size)

        //clear dev2's instruments
        dev2InstrumentService.clearLiveInstruments().await()
        assertEquals(0, dev1InstrumentService.getLiveInstruments().await().size)
        assertEquals(0, dev2InstrumentService.getLiveInstruments().await().size)
    }
}
