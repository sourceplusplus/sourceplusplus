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

import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser
import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.impl.DiscoveryImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.SourceServices.Utilize
import spp.protocol.extend.TCPServiceFrameParser
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.status.InstanceConnection
import java.io.File
import java.util.*

@ExtendWith(VertxExtension::class)
open class PlatformIntegrationTest {

    companion object {
        const val SYSTEM_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJzeXN0ZW0iLCJjcmVhdGVkX2F0IjoxNjU0NTg4MzUwMTg1L" +
                    "CJleHBpcmVzX2F0IjoxNjg2MTI0MzUwMTg1LCJpYXQiOjE2NTQ1ODgzNTB9.M0vnTwJo9h_cGDqkvPA8cndsSZqjZHOb4Fpsel" +
                    "xSzOBhQGlJh0xj7SJjfuPSVPKn4rRuZ6UGP5sKr323q62NEaM5T8h9dnXyyGUVxMwMKRvmyqJI36ZHELVy4egtFMGBTuEmExKF" +
                    "PAtupgQYkjro5ZMI4yRC_KH_YK3YFiCP7tZWPGJGReK2QiErN-ECU-6J3AotK_jvvDFqBFEavW4Si0Far-mK1kNIHqbDbuZoEH" +
                    "iUAbTyLKJJcCsRm0TZr1qMlfxz2py0iz3_fGuFu8dxLjRX-uyV0qYDpIfPjyV-706dcLjqE3fJ8jI2byrenjDuUGjbNZh9pxV6" +
                    "ZQh8YeJ77R95N_pcIH20iLkZrRqaqyYCZUNO-v3xDyMhuNUaKqBKCfp8iCOxfjzDzSbgGGHH4KgDEN-0-kSSLZKgerViezCncX" +
                    "JFBkvtEYOm3ZG53Ubj31buMCjaQniDuDtbrWtFYUcC4rXY31z-cQDqKM44jHf95mAYDcsc8dFL_yjYVMnZtLIXUa5qSiBqL5ZF" +
                    "5HLHvhK5D9b-TPHltqwg7PzPmv_wYmwSD0_aCQ7ZJsKONVvFDQTvuJGXQ7tsMbjloS6qd_jr1UVajbRXu3shgzWmEPLAC651n0" +
                    "CgCTKYpWSM5VchWbdZdMrwYTeT9lM7QlnmMRONU97kjlvgQ3wiJyShiHo"

        const val TEST_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJ0ZXN0IiwiY3JlYXRlZF9hdCI6MTY1NDU4OTM0NTkwNywiZ" +
                    "XhwaXJlc19hdCI6MTY4NjEyNTM0NTkwNywiaWF0IjoxNjU0NTg5MzQ1fQ.UXC-Y9puYPyYdct7aZlSSiyscUC0VgZKatEeO35l" +
                    "kFyORpSGaJRxTS1Sbc6wOysDqIy714adpSKiS34V4WCd385-EF4UNQ2dwvCierCqW1rPgVaaFQKw1bixNolFw1Z7Gz9b_JTySE" +
                    "X1K3I5qJfZwTHAi00tSRJx6z6mSEddltfHlcc19S14CUDc93-7wfmf8nr33OYojfj3wIjsApdIojqmrJQi40-X5IBA8gYASPNp" +
                    "geq-ZRBgnCXxuTHD0hj3p1oIy5iYXvmwiw_Vi147cpqQBFS-don2IDwoWu3pw2lzSfP-UIZftgUsaLvRkLfKKxto9HdzV3ZXs3" +
                    "RjI5cQUxX7TIjiT21BjE8hYK991DdisVwcWZ6fPNU49jQ3-iDcETSaBKJDdthpZP2acdAQ0453Wh0vjRyImN819_4wqgS8zHm1" +
                    "hstmofxuyCrn7altCmnusg4AAkvdSKEsq-XFqm3Xb30eeCFCq8qxHuufdgbv3cWaHuPhFceET70dzht9cKKzXBKTYL84TXQ_Jm" +
                    "g0WLIEq87RzuhRVA08LAin7roXpPNy2C5R8kJexakzPGHVsJdkQly9G7YbtEDN-QrjgLeZN9H7IXaFZxooVcW7FqOVzIjW3eVx" +
                    "FLIa7es6009abAiEU1WPJoRqPO8V4SKv67hVYVhZYTc1lIwndW_7Z4w"

        private val log = LoggerFactory.getLogger(PlatformIntegrationTest::class.java)
        private val INSTANCE_ID = UUID.randomUUID().toString()
        val vertx = Vertx.vertx()!!
        lateinit var tcpSocket: NetSocket
        lateinit var discovery: ServiceDiscovery
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"

        @BeforeAll
        @JvmStatic
        fun setup() {
            val platformPort = 5455
            val useSsl = true
            val trustAll = true
            val platformCertificateFile = "../docker/e2e/config/spp-platform.crt"
            val myCaAsABuffer = Buffer.buffer(File(platformCertificateFile).readText())
            val options = when {
                myCaAsABuffer != null -> NetClientOptions()
                    .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                    .setSsl(true).setPemTrustOptions(PemTrustOptions().addCertValue(myCaAsABuffer))
                useSsl -> NetClientOptions()
                    .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                    .setSsl(true).setTrustAll(trustAll)
                else -> NetClientOptions()
                    .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
            }

            runBlocking {
                withTimeout(5000) {
                    tcpSocket = vertx.createNetClient(options).connect(platformPort, platformHost).await()
                }
                tcpSocket.handler(FrameParser(TCPServiceFrameParser(vertx, tcpSocket)))

                //send marker connected status
                val replyAddress = UUID.randomUUID().toString()
                val pc = InstanceConnection(INSTANCE_ID, System.currentTimeMillis())
                val consumer: MessageConsumer<Boolean> = vertx.eventBus().localConsumer(replyAddress)

                val promise = Promise.promise<Void>()
                consumer.handler {
                    promise.complete()
                    consumer.unregister()
                }

                FrameHelper.sendFrame(
                    BridgeEventType.SEND.name.lowercase(), PlatformAddress.MARKER_CONNECTED,
                    replyAddress, JsonObject().put("auth-token", SYSTEM_JWT_TOKEN), true,
                    JsonObject.mapFrom(pc), tcpSocket
                )
                withTimeout(5000) {
                    promise.future().await()
                }

                vertx.eventBus().localConsumer<JsonObject>(Utilize.LIVE_INSTRUMENT) { resp ->
                    val forwardAddress = resp.address()
                    val forwardMessage = resp.body()
                    val replyAddress = UUID.randomUUID().toString()

                    if (log.isTraceEnabled) {
                        log.trace("Started listening at {}", replyAddress)
                    }
                    val tempConsumer = vertx.eventBus().localConsumer<Any>(replyAddress)
                    tempConsumer.handler {
                        resp.reply(it.body())
                        tempConsumer.unregister()

                        if (log.isTraceEnabled) {
                            log.trace("Finished listening at {}", replyAddress)
                        }
                    }

                    val headers = JsonObject()
                    resp.headers().entries().forEach { headers.put(it.key, it.value) }
                    FrameHelper.sendFrame(
                        BridgeEventType.SEND.name.lowercase(), forwardAddress,
                        replyAddress, headers, true, forwardMessage, tcpSocket
                    )
                }

                //register listener
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.lowercase(),
                    toLiveInstrumentSubscriberAddress("system"), null,
                    JsonObject().put("auth-token", SYSTEM_JWT_TOKEN), null, null, tcpSocket
                )

                discovery = DiscoveryImpl(
                    vertx,
                    ServiceDiscoveryOptions().setBackendConfiguration(
                        JsonObject().put("backend-name", "test-service-discovery")
                    )
                )
            }
        }
    }
}
