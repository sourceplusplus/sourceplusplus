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
import spp.protocol.ProtocolMarshaller
import spp.protocol.SourceMarkerServices
import spp.protocol.SourceMarkerServices.Utilize
import spp.protocol.extend.TCPServiceFrameParser
import spp.protocol.platform.PlatformAddress
import spp.protocol.status.MarkerConnection
import java.io.File
import java.util.*

@ExtendWith(VertxExtension::class)
open class PlatformIntegrationTest {

    companion object {
        const val SYSTEM_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJzeXN0ZW0iLCJjcmVhdGVkX2F0IjoxNjIyNDIxMzY0ODY4" +
                    "LCJleHBpcmVzX2F0IjoxNjUzOTU3MzY0ODY4LCJpYXQiOjE2MjI0MjEzNjR9.ZVHtxQkfCF7KM_dyDOgawbwpEAsmnCWB4c8I" +
                    "52svPvVc-SlzkEe0SYrNufNPniYZeM3IF0Gbojl_DSk2KleAz9CLRO3zfegciXKeEEvGjsNOqfQjgU5yZtBWmTimVXq5QoZME" +
                    "GuAojACaf-m4J0H7o4LQNGwrDVA-noXVE0Eu84A5HxkjrRuFlQWv3fzqSRC_-lI0zRKuFGD-JkIfJ9b_wP_OjBWT6nmqkZn_J" +
                    "mK7UwniTUJjocszSA2Ma3XLx2xVPzBcz00QWyjhIyiftxNQzgqLl1XDVkRtzXUIrHnFCR8BcgR_PsqTBn5nH7aCp16zgmkkbO" +
                    "pmJXlNpDSVz9zUY4NOrB1jTzDB190COrfCXddb7JO6fmpet9_Zd3kInJx4XsT3x7JfBSWr9FBqFoUmNkgIWjkbN1TpwMyizXA" +
                    "Sp1nOmwJ64FDIbSpfpgUAqfSWXKZYhSisfnBLEyHCjMSPzVmDh949w-W1wU9q5nGFtrx6PTOxK_WKOiWU8_oeTjL0pD8pKXqJ" +
                    "MaLW-OIzfrl3kzQNuF80YT-nxmNtp5PrcxehprlPmqSB_dyTHccsO3l63d8y9hiIzfRUgUjTJbktFn5t41ADARMs_0WMpIGZJ" +
                    "yxcVssstt4J1Gj8WUFOdqPsIKigJZMn3yshC5S-KY-7S0dVd0VXgvpPqmpb9Q9Uho"

        const val TEST_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJ0ZXN0IiwiY3JlYXRlZF9hdCI6MTYyMjQyNTY0ODIyNywiZ" +
                    "XhwaXJlc19hdCI6MTY1Mzk2MTY0ODIyNywiaWF0IjoxNjIyNDI1NjQ4fQ.hdWHNVe9No8iFjgKLT4UWp3hm8qCUg-bkymHcSns" +
                    "Vs_Cup2sLvqQOPiWdpHP2yK8BNJVprCG-ZTelDSkqYBC3-o4fhPHzisiYVdLso3sc-cIO-4eJevZ9o1BxUcxMmcESE4EDkIn7P" +
                    "SmKakPOBc0k003cScYuxF0uSc0t4Zg-nxtZl9J771QfXAT_LIpRyQHTRKWHDjLTNtX_haCMDhIQ8bC6mtG2THMN4x6Dx5wAQ7h" +
                    "WPLx1VBGLEXeEG3A-dpXQxxCS8swsMkPOZ0WDDgCV_zZ30RakmZGc8hUrnE0wCfF2WNVNJuCxvJlMclHPmwD6Vsqg9Gt8E_OLl" +
                    "a7GBx_ukiSq1xJwo3bBUu5d7VxEZv6fS3511aaGUfjSCnB_SGl23dLyjs_h9Lc4Wh8jd95Cmnbmg-NdrgV8havZii8M73MdFIQ" +
                    "MKFSgwfI7mmOEqPdjoQpzNzv0rYGdgiLfPQwGTsApFmz65Z-mnHdIXDYbXUTxwaH39zzM53godAtOeYbsRFPNUoOJta920aeo7" +
                    "GD34CjXerSSBHyp_Em1K7lM1wWZBWFQDUr6Je7OZnl2uPUkmGNpm-lxhx2w9_Za9Ylq9vuCCV2u7TfWTBY0-nBB-f0sMjrbeaq" +
                    "M2NYTigNAY8PqDKn5IiLBNiGkdGgdQNRiiz0vuxPumt268aa6_Kf_EY"

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
                val pc = MarkerConnection(INSTANCE_ID, System.currentTimeMillis())
                val consumer: MessageConsumer<Boolean> = vertx.eventBus().localConsumer("local.$replyAddress")

                val promise = Promise.promise<Void>()
                consumer.handler {
                    promise.complete()
                    consumer.unregister()
                }

                FrameHelper.sendFrame(
                    BridgeEventType.SEND.name.lowercase(), PlatformAddress.MARKER_CONNECTED.address,
                    replyAddress, JsonObject(), true, JsonObject.mapFrom(pc), tcpSocket
                )
                withTimeout(5000) {
                    promise.future().await()
                }

                vertx.eventBus().localConsumer<JsonObject>(Utilize.LIVE_INSTRUMENT) { resp ->
                    val forwardAddress = resp.address()
                    val forwardMessage = resp.body()
                    val replyAddress = UUID.randomUUID().toString()

                    if (log.isTraceEnabled) {
                        log.trace("Started listening at {}", "local.$replyAddress")
                    }
                    val tempConsumer = vertx.eventBus().localConsumer<Any>("local.$replyAddress")
                    tempConsumer.handler {
                        resp.reply(it.body())
                        tempConsumer.unregister()

                        if (log.isTraceEnabled) {
                            log.trace("Finished listening at {}", "local.$replyAddress")
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
                    SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER, JsonObject(), tcpSocket
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
