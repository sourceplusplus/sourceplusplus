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
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType
import spp.protocol.platform.auth.RedactionType.IDENTIFIER_MATCH
import spp.protocol.platform.auth.RolePermission
import spp.protocol.platform.general.Service
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.listen.addBreakpointHitListener

class BreakpointRedactionTest : LiveInstrumentIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun doTest() {
        startEntrySpan("doTest")
        val password = "1234567890"
        val ssn = "555-55-5555"
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `breakpoint redaction`() = runBlocking {
        setupLineLabels {
            doTest()
        }

        val testContext = VertxTestContext()
        vertx.addBreakpointHitListener(testNameAsInstrumentId) { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(3, topFrame.variables.size)

                //password
                val passwordVariable = topFrame.variables.first { it.name == "password" }
                assertEquals(
                    "*password*",
                    passwordVariable.value
                )

                //ssn
                val ssnVariable = topFrame.variables.first { it.name == "ssn" }
                assertEquals(
                    "*ssn*",
                    ssnVariable.value
                )
            }

            //test passed
            testContext.completeNow()
        }.await()

        //setup redaction
        val developer = managementService.addDeveloper("bp-redaction-developer-" + System.currentTimeMillis()).await()
        val role = DeveloperRole.fromString("bp-redaction-role-" + System.currentTimeMillis())
        managementService.addRole(role).await()
        managementService.addRolePermission(role, RolePermission.ADD_LIVE_BREAKPOINT).await()
        val idMatchRedaction = managementService.addDataRedaction(
            "bp-redaction-password-" + System.currentTimeMillis(),
            IDENTIFIER_MATCH,
            "password",
            "*password*"
        ).await()
        managementService.addRoleDataRedaction(role, idMatchRedaction.id).await()
        val ssnValueRedaction = managementService.addDataRedaction(
            "bp-redaction-ssn-" + System.currentTimeMillis(),
            RedactionType.VALUE_REGEX,
            "\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b",
            "*ssn*"
        ).await()
        managementService.addRoleDataRedaction(role, ssnValueRedaction.id).await()
        managementService.addDeveloperRole(developer.id, role).await()

        //add live breakpoint
        val accessToken = managementService.getAccessToken(developer.authorizationCode!!).await()
        val instrumentService = LiveInstrumentService.createProxy(vertx, accessToken)
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                id = testNameAsInstrumentId,
                location = LiveSourceLocation(
                    BreakpointRedactionTest::class.java.name,
                    getLineNumber("done"),
                    Service.fromName("spp-test-probe")
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        doTest()

        errorOnTimeout(testContext)
    }
}
