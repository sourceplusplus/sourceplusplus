package integration

import spp.protocol.SourceMarkerServices
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.service.live.LiveInstrumentService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import io.vertx.serviceproxy.ServiceProxyBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.platform.core.auth.error.InstrumentAccessDenied
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class JWTTest : IntegrationTest() {

    private val log = LoggerFactory.getLogger(JWTTest::class.java)

    @Test
    fun verifySuccessful() = runBlocking {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("integration.JWTTest", 1),
                condition = "1 == 2"
            )
        ) {
            if (it.succeeded()) {
                instrumentService.removeLiveInstrument(it.result().id!!) {
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
    fun verifyUnsuccessful() = runBlocking {
        val testContext = VertxTestContext()
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        val client = WebClient.create(
            vertx,
            WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        val addDevResp = client.post(5445, platformHost, "/graphql")
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
        val addRoleResp = client.post(5445, platformHost, "/graphql")
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
        val addDeveloperRoleResp = client.post(5445, platformHost, "/graphql")
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
        val addAccessPermissionResp = client.post(5445, platformHost, "/graphql")
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
        val addRoleAccessPermissionResp = client.post(5445, platformHost, "/graphql")
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

        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(TEST_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                LiveSourceLocation("integration.JWTTest", 2),
                condition = "-41 == -12"
            )
        ) {
            if (it.failed()) {
                if (it.cause().cause is InstrumentAccessDenied) {
                    //verify not available
                    instrumentService.removeLiveInstruments(LiveSourceLocation("integration.JWTTest", 2)) {
                        if (it.succeeded()) {
                            testContext.verify {
                                assertTrue(it.result().isEmpty())
                            }
                            testContext.completeNow()
                        } else {
                            testContext.failNow(it.cause())
                        }
                    }
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
