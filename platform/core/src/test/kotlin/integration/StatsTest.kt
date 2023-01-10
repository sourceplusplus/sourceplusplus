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

import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.service.SourceServices

class StatsTest : PlatformIntegrationTest() {

    @Test
    fun verifyStats(): Unit = runBlocking {
        val client = WebClient.create(vertx, WebClientOptions())
        val authToken = managementService.getAuthToken("change-me").await()
        val result = client.get(12800, platformHost, "/stats")
            .bearerTokenAuthentication(authToken).send().await()
            .bodyAsJsonObject().getJsonObject("platform")

        assertNotNull(result.getInteger("connected-markers"))
        assertNotNull(result.getInteger("connected-probes"))

        val services = result.getJsonObject("services")
        val coreServices = services.getJsonObject("core")
        assertNotNull(coreServices.getInteger(SourceServices.LIVE_MANAGEMENT))
        assertNotNull(coreServices.getInteger(SourceServices.LIVE_INSTRUMENT))
        assertNotNull(coreServices.getInteger(SourceServices.LIVE_VIEW))
        coreServices.map.forEach {
            assertEquals(1, it.value, "Missing ${it.key}")
        }

        //clean up
        client.close()
    }
}
