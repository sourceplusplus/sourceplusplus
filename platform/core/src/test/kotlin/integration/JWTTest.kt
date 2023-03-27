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
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.platform.auth.AccessType.BLACK_LIST
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RolePermission.ADD_LIVE_BREAKPOINT
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.error.InstrumentAccessDenied
import spp.protocol.service.error.PermissionAccessDenied

@ExtendWith(VertxExtension::class)
class JWTTest : PlatformIntegrationTest() {

    @BeforeEach
    fun reset(): Unit = runBlocking {
        managementService.reset().await()
    }

    @Test
    fun verifySuccessful() = runBlocking {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("integration.JWTTest", 1),
                condition = "1 == 2"
            )
        ).onComplete {
            if (it.succeeded()) {
                instrumentService.removeLiveInstrument(it.result().id!!).onComplete {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun verifyUnsuccessfulPermission() = runBlocking {
        val testContext = VertxTestContext()
        val test2Dev = managementService.addDeveloper("test2").await()
        val accessToken = managementService.getAccessToken(test2Dev.authorizationCode!!).await()
        val instrumentService = LiveInstrumentService.createProxy(vertx, accessToken)
        instrumentService.getLiveInstruments().onComplete {
            if (it.failed()) {
                val cause = ServiceExceptionConverter.fromEventBusException(it.cause().message!!)
                if (cause is PermissionAccessDenied) {
                    testContext.completeNow()
                } else {
                    testContext.failNow(it.cause())
                }
            } else {
                testContext.failNow("Got live instrument with invalid permission")
            }
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun verifyUnsuccessfulAccess() = runBlocking {
        val testContext = VertxTestContext()
        val testDev = managementService.addDeveloper("test").await()
        managementService.addRole(DeveloperRole.fromString("tester")).await()
        managementService.addDeveloperRole(testDev.id, DeveloperRole.fromString("tester")).await()
        managementService.addRolePermission(DeveloperRole.fromString("tester"), ADD_LIVE_BREAKPOINT).await()
        val accessPermission = managementService.addAccessPermission(listOf("integration.JWTTest"), BLACK_LIST).await()
        managementService.addRoleAccessPermission(DeveloperRole.fromString("tester"), accessPermission.id).await()
        val accessToken = managementService.getAccessToken(testDev.authorizationCode!!).await()
        val instrumentService = LiveInstrumentService.createProxy(vertx, accessToken)
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("integration.JWTTest", 2),
                condition = "-41 == -12"
            )
        ).onComplete {
            if (it.failed()) {
                val cause = ServiceExceptionConverter.fromEventBusException(it.cause().message!!)
                if (cause is InstrumentAccessDenied) {
                    testContext.completeNow()
                } else {
                    testContext.failNow(it.cause())
                }
            } else {
                testContext.failNow("Got live instrument on black listed location")
            }
        }

        errorOnTimeout(testContext)
    }
}
