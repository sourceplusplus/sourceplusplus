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
package spp.platform.bridge

import integration.PlatformIntegrationTest
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.WebSocketConnectOptions
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class InstanceBridgeIT : PlatformIntegrationTest() {

    @Test
    fun `invalid connections get kicked`(): Unit = runBlocking {
        val testContext = VertxTestContext()

        //connect new probe
        val client = vertx.createHttpClient(
            HttpClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(12800)
        )
        val wsOptions = WebSocketConnectOptions()
            .setURI("http://localhost:12800/probe/eventbus/websocket")
        val ws = client.webSocket(wsOptions).await()

        //success on kicked
        ws.closeHandler {
            testContext.completeNow()
        }

        //send ping
        val ping = JsonObject()
            .put("type", "ping")
            .put("body", true)
        ws.writeTextMessage(ping.toString())

        //wait for response
        errorOnTimeout(testContext)
    }
}
