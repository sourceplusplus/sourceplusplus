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

import integration.PlatformIntegrationTest
import io.vertx.core.Promise
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.WebSocketConnectOptions
import io.vertx.core.http.WebSocketFrame
import io.vertx.core.json.JsonObject
import io.vertx.junit5.Timeout
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import spp.protocol.platform.PlatformAddress.PROBE_CONNECTED
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.auth.ClientAccess
import spp.protocol.platform.status.InstanceConnection
import java.util.*

class ProbeBridgeITTest : PlatformIntegrationTest() {

    companion object {
        private val log = LoggerFactory.getLogger(ProbeBridgeITTest::class.java)
    }

    private var clientAccess: ClientAccess? = null

    @BeforeEach
    fun addClientAccess(): Unit = runBlocking {
        if (clientAccess == null) {
            clientAccess = managementService.addClientAccess().await()
        }
    }

    @AfterEach
    fun removeClientAccess(): Unit = runBlocking {
        if (clientAccess != null) {
            managementService.removeClientAccess(clientAccess!!.id).await()
            clientAccess = null
        }
    }

    @Test
    @Timeout(10)
    fun testProbeCounter(): Unit = runBlocking {
        val testContext = VertxTestContext()

        //get probe count
        val probeCount = managementService.getStats().await()
            .getJsonObject("platform").getInteger("connected-probes")

        //connect new probe
        val client = vertx.createHttpClient(
            HttpClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(12800)
        )
        val wsOptions = WebSocketConnectOptions()
            .setURI("http://localhost:12800/probe/eventbus/websocket")
        val ws = client.webSocket(wsOptions).await()

        //send connected message
        val replyAddress = UUID.randomUUID().toString()
        val msg = JsonObject()
            .put("type", "send")
            .put("address", PROBE_CONNECTED)
            .put("replyAddress", replyAddress)
            .put(
                "headers", JsonObject()
                    .put("client_id", clientAccess!!.id)
                    .put("client_secret", clientAccess!!.secret)
            )
        val pc = InstanceConnection("test-probe-id", System.currentTimeMillis())
        msg.put("body", JsonObject.mapFrom(pc))
        ws.writeFrame(WebSocketFrame.textFrame(msg.encode(), true))

        val connectPromise = Promise.promise<Void>()
        ws.handler { buff ->
            val str: String = buff.toString()
            val received = JsonObject(str)
            val rec: Any = received.getValue("body")
            testContext.verify {
                assertEquals(true, rec)
            }
            connectPromise.complete()
        }
        connectPromise.future().await()

        delay(2000) //ensure probe is connected

        //verify probe count increased
        val increasedProbeCount = managementService.getStats().await()
            .getJsonObject("platform").getInteger("connected-probes")
        testContext.verify {
            assertEquals(probeCount + 1, increasedProbeCount)
        }

        //disconnect probe
        ws.close().await()
        client.close().await()
        delay(2000) //ensure probe is disconnected

        //verify probe count decreased
        val decreasedProbeCount = managementService.getStats().await()
            .getJsonObject("platform").getInteger("connected-probes")
        testContext.verify {
            assertEquals(probeCount, decreasedProbeCount)
        }

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
        log.info("testProbeCounter finished")
    }

    @Test
    fun testInvalidAccess_connectedMessage(): Unit = runBlocking {
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

        //send connected message
        val replyAddress = UUID.randomUUID().toString()
        val msg = JsonObject()
            .put("type", "send")
            .put("address", PROBE_CONNECTED)
            .put("replyAddress", replyAddress)
            .put(
                "headers", JsonObject()
                    .put("client_id", "invalid-id")
                    .put("client_secret", "invalid-secret")
            )
        val pc = InstanceConnection("test-probe-id", System.currentTimeMillis())
        msg.put("body", JsonObject.mapFrom(pc))
        ws.writeFrame(WebSocketFrame.textFrame(msg.encode(), true))

        val connectPromise = Promise.promise<Void>()
        ws.handler {
            val received = JsonObject(it.toString())
            testContext.verify {
                assertEquals("err", received.getString("type"))
                assertEquals("rejected", received.getString("body"))
            }
            connectPromise.complete()
        }
        connectPromise.future().await()

        //disconnect probe
        ws.close().await()
        client.close().await()

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
        log.info("testInvalidAccess_connectedMessage finished")
    }

    @Test
    fun testInvalidAccess_registerRemote(): Unit = runBlocking {
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

        //skip connected message and try to register remote
        val msg = JsonObject()
            .put("type", "register")
            .put("address", ProbeAddress.LIVE_INSTRUMENT_REMOTE)
        val pc = InstanceConnection("test-probe-id", System.currentTimeMillis())
        msg.put("body", JsonObject.mapFrom(pc))
        ws.writeFrame(WebSocketFrame.textFrame(msg.encode(), true))

        val connectPromise = Promise.promise<Void>()
        ws.handler {
            val received = JsonObject(it.toString())
            testContext.verify {
                assertEquals("err", received.getString("type"))
                assertEquals("rejected", received.getString("body"))
            }
            connectPromise.complete()
        }
        connectPromise.future().await()

        //disconnect probe
        ws.close().await()
        client.close().await()

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
        log.info("testInvalidAccess_registerRemote finished")
    }

    @Test
    fun testUpdateActiveProbeMetadata(): Unit = runBlocking {
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

        //send connected message
        val replyAddress = UUID.randomUUID().toString()
        val msg = JsonObject()
            .put("type", "send")
            .put("address", PROBE_CONNECTED)
            .put("replyAddress", replyAddress)
            .put(
                "headers", JsonObject()
                    .put("client_id", clientAccess!!.id)
                    .put("client_secret", clientAccess!!.secret)
            )
        val pc = InstanceConnection("testUpdateActiveProbeMetadata", System.currentTimeMillis())
        msg.put("body", JsonObject.mapFrom(pc))
        ws.writeFrame(WebSocketFrame.textFrame(msg.encode(), true))

        val connectPromise = Promise.promise<Void>()
        ws.handler { buff ->
            val str: String = buff.toString()
            val received = JsonObject(str)
            val rec: Any = received.getValue("body")
            testContext.verify {
                assertEquals(true, rec)
            }
            connectPromise.complete()
        }
        connectPromise.future().await()

        delay(2000) //ensure probe is connected

        //get probe metadata
        val probeConnection = managementService.getActiveProbe("testUpdateActiveProbeMetadata").await()
        testContext.verify {
            assertNotNull(probeConnection)
            assertEquals("testUpdateActiveProbeMetadata", probeConnection!!.instanceId)
        }
        val probeMetadata = probeConnection!!.meta
        assertEquals(emptyMap<String, Any>(), probeMetadata)

        //add probe metadata
        val result = managementService.updateActiveProbeMetadata(
            "testUpdateActiveProbeMetadata",
            JsonObject(mapOf("key" to "value"))
        ).await()
        testContext.verify {
            assertEquals("testUpdateActiveProbeMetadata", result.instanceId)
            assertEquals(mapOf("key" to "value"), result.meta)
        }

        //update probe metadata
        val result2 = managementService.updateActiveProbeMetadata(
            "testUpdateActiveProbeMetadata",
            JsonObject(mapOf("key" to "value2"))
        ).await()
        testContext.verify {
            assertEquals("testUpdateActiveProbeMetadata", result2.instanceId)
            assertEquals(mapOf("key" to "value2"), result2.meta)
        }

        //get probe metadata
        val probeConnection2 = managementService.getActiveProbe("testUpdateActiveProbeMetadata").await()
        testContext.verify {
            assertNotNull(probeConnection2)
            assertEquals("testUpdateActiveProbeMetadata", probeConnection2!!.instanceId)
            assertEquals(mapOf("key" to "value2"), probeConnection2.meta)
        }

        //disconnect probe
        ws.close().await()
        client.close().await()

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
        log.info("testUpdateActiveProbeMetadata finish")
    }
}
