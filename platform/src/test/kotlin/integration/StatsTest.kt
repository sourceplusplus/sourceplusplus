/*
 * Source++, the open-source live coding platform.
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
                    assertEquals(2, result.getInteger("connected-probes"))
                    val services = result.getJsonObject("services")
                    services.getJsonObject("core").map.forEach {
                        assertEquals(1, it.value, "Missing ${it.key}")
                    }
                    services.getJsonObject("probe").map.forEach {
                        assertEquals(2, it.value, "Missing ${it.key}")
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
