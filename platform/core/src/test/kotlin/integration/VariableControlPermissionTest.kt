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

import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.variable.LiveVariableControl
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RolePermission
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.error.PermissionAccessDenied
import java.util.*

class VariableControlPermissionTest : PlatformIntegrationTest() {

    @Test
    fun verifyPermission() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val dev = managementService.addDeveloper("bp-permission-test-$uuid").await()
        val devRole = DeveloperRole.fromString("bp-permission-test-$uuid")
        managementService.addRole(devRole).await()
        managementService.addDeveloperRole(dev.id, devRole).await()
        managementService.addRolePermission(devRole, RolePermission.ADD_LIVE_BREAKPOINT).await()
        managementService.addRolePermission(devRole, RolePermission.REMOVE_LIVE_INSTRUMENT).await()

        val accessToken = managementService.getAccessToken(dev.authorizationCode!!).await()
        val instrumentService = LiveInstrumentService.createProxy(vertx, accessToken)
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                instrumentService.addLiveBreakpoint(
                    LiveBreakpoint(
                        variableControl = LiveVariableControl(
                            maxObjectDepth = 1
                        ),
                        location = LiveSourceLocation("integration.BreakpointPermissionTest", 1),
                        condition = "1 == 2",
                        id = testNameAsUniqueInstrumentId
                    )
                ).await()
            }
        }
        val ebException = ServiceExceptionConverter.fromEventBusException(exception.message!!)
        assertTrue(ebException is PermissionAccessDenied)
        assertEquals(RolePermission.BREAKPOINT_VARIABLE_CONTROL, (ebException as PermissionAccessDenied).permission)

        //add permission
        managementService.addRolePermission(devRole, RolePermission.BREAKPOINT_VARIABLE_CONTROL).await()

        //verify successful
        val instrument = instrumentService.addLiveBreakpoint(
            LiveBreakpoint(
                variableControl = LiveVariableControl(
                    maxObjectDepth = 1
                ),
                location = LiveSourceLocation("integration.BreakpointPermissionTest", 1),
                condition = "1 == 2",
                id = testNameAsUniqueInstrumentId
            )
        ).await()
        assertNotNull(instrument)
        assertEquals(LiveVariableControl(maxObjectDepth = 1), instrument.variableControl)

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(instrument.id!!).await())
    }
}
