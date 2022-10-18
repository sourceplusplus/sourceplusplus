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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.marshall.ServiceExceptionConverter
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.error.InstrumentAccessDenied
import spp.protocol.service.error.PermissionAccessDenied
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class JWTTest : PlatformIntegrationTest() {

    private val log = LoggerFactory.getLogger(JWTTest::class.java)

    @BeforeEach
    fun reset(): Unit = runBlocking {
        //managementService.reset() //todo: this
    }

    @Test
    fun verifySuccessful() = runBlocking {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("integration.JWTTest", 1),
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

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun verifyUnsuccessfulPermission() = runBlocking {
        val testContext = VertxTestContext()
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        val client = WebClient.create(
            vertx,
            WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        val addDevResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$id: String!) {\n" +
                            "  addDeveloper(id: \$id) {\n" +
                            "    id\n" +
                            "    accessToken\n" +
                            "  }\n" +
                            "}\n"
                ).put("variables", JsonObject().put("id", "test2"))
            ).await().bodyAsJsonObject()
        log.info("Add dev resp: {}", addDevResp)
        assertFalse(addDevResp.containsKey("errors"))
        val addRoleResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$role: String!) {\n" +
                            "  addRole(role: \$role)\n" +
                            "}\n"
                ).put("variables", JsonObject().put("role", "tester2"))
            ).await().bodyAsJsonObject()
        log.info("Add role resp: {}", addRoleResp)
        assertFalse(addRoleResp.containsKey("errors"))
        val addDeveloperRoleResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$id: String!, \$role: String!) {\n" +
                            "  addDeveloperRole(id: \$id, role: \$role)\n" +
                            "}\n"
                ).put("variables", JsonObject().put("id", "test2").put("role", "tester2"))
            ).await().bodyAsJsonObject()
        log.info("Add developer role resp: {}", addDeveloperRoleResp)
        assertFalse(addDeveloperRoleResp.containsKey("errors"))

        val instrumentService = LiveInstrumentService.createProxy(vertx, TEST_JWT_TOKEN)
        instrumentService.getLiveInstruments(null).onComplete {
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

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun verifyUnsuccessfulAccess() = runBlocking {
        val testContext = VertxTestContext()
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        val client = WebClient.create(
            vertx,
            WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        val addDevResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$id: String!) {\n" +
                            "  addDeveloper(id: \$id) {\n" +
                            "    id\n" +
                            "    accessToken\n" +
                            "  }\n" +
                            "}\n"
                ).put("variables", JsonObject().put("id", "test"))
            ).await().bodyAsJsonObject()
        log.info("Add dev resp: {}", addDevResp)
        assertFalse(addDevResp.containsKey("errors"))
        val addRoleResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$role: String!) {\n" +
                            "  addRole(role: \$role)\n" +
                            "}\n"
                ).put("variables", JsonObject().put("role", "tester"))
            ).await().bodyAsJsonObject()
        log.info("Add role resp: {}", addRoleResp)
        assertFalse(addRoleResp.containsKey("errors"))
        val addDeveloperRoleResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$id: String!, \$role: String!) {\n" +
                            "  addDeveloperRole(id: \$id, role: \$role)\n" +
                            "}\n"
                ).put("variables", JsonObject().put("id", "test").put("role", "tester"))
            ).await().bodyAsJsonObject()
        log.info("Add developer role resp: {}", addDeveloperRoleResp)
        assertFalse(addDeveloperRoleResp.containsKey("errors"))
        val addRolePermissionResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$role: String!, \$permission: String!) {\n" +
                            "  addRolePermission(role: \$role, permission: \$permission)\n" +
                            "}\n\n"
                ).put("variables", JsonObject().put("role", "tester").put("permission", "ADD_LIVE_BREAKPOINT"))
            ).await().bodyAsJsonObject()
        log.info("Add role permission resp: {}", addRolePermissionResp)
        assertFalse(addRolePermissionResp.containsKey("errors"))
        val addAccessPermissionResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$locationPatterns: [String!], \$type: AccessType!) {\n" +
                            "  addAccessPermission(locationPatterns: \$locationPatterns, type: \$type) {\n" +
                            "    id\n" +
                            "    locationPatterns\n" +
                            "    type\n" +
                            "  }\n" +
                            "}\n"
                ).put(
                    "variables", JsonObject()
                        .put("locationPatterns", JsonArray().add("integration.JWTTest"))
                        .put("type", "BLACK_LIST")
                )
            ).await().bodyAsJsonObject()
        log.info("Add access permission resp: {}", addAccessPermissionResp)
        assertFalse(addAccessPermissionResp.containsKey("errors"))
        val addRoleAccessPermissionResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "mutation (\$role: String!, \$accessPermissionId: String!) {\n" +
                            "  addRoleAccessPermission(role: \$role, accessPermissionId: \$accessPermissionId)\n" +
                            "}\n"
                ).put(
                    "variables", JsonObject().put("role", "tester").put(
                        "accessPermissionId",
                        addAccessPermissionResp.getJsonObject("data")
                            .getJsonObject("addAccessPermission").getString("id")
                    )
                )
            ).await().bodyAsJsonObject()
        log.info("Add role access permission resp: {}", addRoleAccessPermissionResp)
        assertFalse(addRoleAccessPermissionResp.containsKey("errors"))

        val instrumentService = LiveInstrumentService.createProxy(vertx, TEST_JWT_TOKEN)
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("integration.JWTTest", 2),
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

        if (testContext.awaitCompletion(60, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
