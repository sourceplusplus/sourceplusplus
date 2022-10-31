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
package spp.platform.bridge.marker

import integration.PlatformIntegrationTest
import io.vertx.core.Promise
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.WebSocketConnectOptions
import io.vertx.core.http.WebSocketFrame
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.protocol.platform.PlatformAddress.MARKER_CONNECTED
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.SourceServices
import java.util.*

class MarkerBridgeITTest : PlatformIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Test
    fun testMarkerCounter(): Unit = runBlocking(vertx.dispatcher()) {
        val testContext = VertxTestContext()

        //get marker count
        log.info("Getting marker count")
        val markerCount = managementService.getStats().await()
            .getJsonObject("platform").getInteger("connected-markers")
        log.info("Marker count: $markerCount")

        //connect new marker
        val client = vertx.createHttpClient(
            HttpClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(12800)
        )
        val wsOptions = WebSocketConnectOptions()
            .setURI("http://localhost:12800/marker/eventbus/websocket")
        val ws = client.webSocket(wsOptions).await()
        log.info("Connected to marker eventbus")

        //send connected message
        val replyAddress = UUID.randomUUID().toString()
        val msg = JsonObject()
            .put("type", "send")
            .put("address", MARKER_CONNECTED)
            .put("replyAddress", replyAddress)
            .put("headers", JsonObject().put("auth-token", SYSTEM_JWT_TOKEN))
        val pc = InstanceConnection("test-marker-id", System.currentTimeMillis())
        msg.put("body", JsonObject.mapFrom(pc))
        ws.writeFrame(WebSocketFrame.textFrame(msg.encode(), true))
        log.info("Sent connected message")

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

        log.info("Waiting for connection acknowledgment")
        connectPromise.future().await()

        delay(2000) //ensure marker is connected

        //verify marker count increased
        val increasedMarkerCount = managementService.getStats().await()
            .getJsonObject("platform").getInteger("connected-markers")
        testContext.verify {
            assertEquals(markerCount + 1, increasedMarkerCount)
        }

        //disconnect marker
        ws.close().await()
        client.close().await()
        delay(2000) //ensure probe is disconnected

        //verify marker count decreased
        val decreasedMarkerCount = managementService.getStats().await()
            .getJsonObject("platform").getInteger("connected-markers")
        testContext.verify {
            assertEquals(markerCount, decreasedMarkerCount)
        }

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }

    @Test
    fun testInvalidAccess_connectedMessage(): Unit = runBlocking(vertx.dispatcher()) {
        val testContext = VertxTestContext()

        //connect new marker
        val client = vertx.createHttpClient(
            HttpClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(12800)
        )
        val wsOptions = WebSocketConnectOptions()
            .setURI("http://localhost:12800/marker/eventbus/websocket")
        val ws = client.webSocket(wsOptions).await()

        //send connected message
        val replyAddress = UUID.randomUUID().toString()
        val msg = JsonObject()
            .put("type", "send")
            .put("address", MARKER_CONNECTED)
            .put("replyAddress", replyAddress)
            .put("headers", JsonObject().put("auth-token", "invalid-token"))
        val pc = InstanceConnection("test-marker-id", System.currentTimeMillis())
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

        //disconnect marker
        ws.close().await()
        client.close().await()

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }

    @Test
    fun testInvalidAccess_registerSubscriber(): Unit = runBlocking(vertx.dispatcher()) {
        val testContext = VertxTestContext()

        //connect new marker
        val client = vertx.createHttpClient(
            HttpClientOptions()
                .setDefaultHost("localhost")
                .setDefaultPort(12800)
        )
        val wsOptions = WebSocketConnectOptions()
            .setURI("http://localhost:12800/marker/eventbus/websocket")
        val ws = client.webSocket(wsOptions).await()

        //attempt register live instrument subscriber
        val msg = JsonObject()
            .put("type", "register")
            .put("address", SourceServices.Subscribe.LIVE_INSTRUMENT_SUBSCRIBER + ":test-marker-id")
        val pc = InstanceConnection("test-marker-id", System.currentTimeMillis())
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

        //disconnect marker
        ws.close().await()
        client.close().await()

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }
}
