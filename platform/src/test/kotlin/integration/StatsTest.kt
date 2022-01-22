package integration

import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class StatsTest : PlatformIntegrationTest() {

    @Test
    fun verifyStats() {
        val testContext = VertxTestContext()
        val client = WebClient.create(
            vertx, WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        client.get(5445, platformHost, "/stats").bearerTokenAuthentication(SYSTEM_JWT_TOKEN).send().onComplete {
            if (it.succeeded()) {
                val result = it.result().bodyAsJsonObject().getJsonObject("platform")
                testContext.verify {
                    assertEquals(1, result.getInteger("connected-probes"))
                    val services = result.getJsonObject("services")
                    services.getJsonObject("core").map.forEach {
                        assertEquals(1, it.value, "Missing ${it.key}")
                    }
                    services.getJsonObject("probe").map.forEach {
                        assertEquals(1, it.value, "Missing ${it.key}")
                    }
                }

                client.close()
                testContext.completeNow()
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(30, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw RuntimeException(testContext.causeOfFailure())
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
