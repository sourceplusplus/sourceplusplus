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
package spp.platform.bridge.probe

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import integration.PlatformIntegrationTest
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProbeGeneratorITTest : PlatformIntegrationTest() {

    @Test
    fun verifyGeneratedProbeConfig(): Unit = runBlocking {
        val client = WebClient.create(
            vertx, WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        val response = client.get(
            12800, platformHost, "/download/spp-probe.yml?access_token=change-me"
        ).send().await()
        val respBody = response.bodyAsString()
        val jsonObject = JsonObject(
            ObjectMapper().writeValueAsString(YAMLMapper().readValue(respBody, Object::class.java))
        )
        client.close()

        jsonObject.getJsonObject("spp").apply {
            assertEquals(platformHost, getString("platform_host"))
            assertEquals(12800, getInteger("platform_port"))
            getJsonObject("authentication").apply {
                assertEquals("test-id", getString("client_id"))
                assertEquals("test-secret", getString("client_secret"))
            }
        }
    }
}
