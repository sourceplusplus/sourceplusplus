///*
// * Source++, the open-source live coding platform.
// * Copyright (C) 2022 CodeBrig, Inc.
// *
// * This program is free software: you can redistribute it and/or modify
// * it under the terms of the GNU Affero General Public License as published
// * by the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * This program is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU Affero General Public License for more details.
// *
// * You should have received a copy of the GNU Affero General Public License
// * along with this program.  If not, see <https://www.gnu.org/licenses/>.
// */
//package integration
//
//import io.vertx.core.Promise
//import io.vertx.core.Vertx
//import io.vertx.core.eventbus.Message
//import io.vertx.core.eventbus.MessageConsumer
//import io.vertx.core.json.JsonObject
//import io.vertx.core.net.NetClientOptions
//import io.vertx.core.net.NetSocket
//import io.vertx.ext.bridge.BridgeEventType
//import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
//import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser
//import io.vertx.kotlin.coroutines.await
//import kotlinx.coroutines.runBlocking
//import kotlinx.coroutines.withTimeout
//import org.junit.jupiter.api.BeforeAll
//import org.slf4j.LoggerFactory
//import spp.protocol.SourceServices
//import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
//import spp.protocol.SourceServices.Provide.toLiveViewSubscriberAddress
//import spp.protocol.extend.TCPServiceFrameParser
//import spp.protocol.platform.PlatformAddress
//import spp.protocol.platform.status.InstanceConnection
//import java.util.*
//
//open class ProcessorIntegrationTest {
//
//    companion object {
//        const val SYSTEM_JWT_TOKEN =
//            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJzeXN0ZW0iLCJjcmVhdGVkX2F0IjoxNjU3MDM5NzAzOTE1L" +
//                    "CJleHBpcmVzX2F0IjoxNjg4NTc1NzAzOTE1LCJpYXQiOjE2NTcwMzk3MDN9.hKxtqnajBWbWxL2nYoVyp9HeyDfIi5XjRRkJtI" +
//                    "wms6JOfWCWO9TG2ghW7nhv2N7c0G6JMGrelWCfXesZ33z0epz4XcJ6s05gV8EXkQjQPKzPQ770w2QHH4IenUKWBn44r0LxteAd" +
//                    "KGVmaheqJ9Gr4hDN2PzzQS5i_WM34N-ucbfUwQ79rUyQaEcDvgywnL8kUSNDlhnYb2gyVMYC5_QxNDusxCUJq6Kas1qHzmg02t" +
//                    "7ToWNzHCGxa7LWJkgx27BMhFSubq8fMUtzP6YWQs4gXLfvVzc3i5VxevJf7dFWw1VsfpW31qfdkmZp89BueaaZpJh236HMnhxM" +
//                    "CwsbCWKaIZgQqGzFL9sZzH-Aav8AM9CRJYpnN0eTl6Bsqbhh2AsS-EycV_O-9NDA4Ac8ImeaGw4kqMwZSVeSMRhSgaWiwmXASL" +
//                    "gNM6LgKVKSAgPXIKrSEPmo9_mFPMwY8uxnu-J0uGmTa1hQEtmblAXBuZbyP1CtOk_Hmydif5K-jFnOne5fyj-Ju5q7uVjz60FH" +
//                    "u5TQSJZX2U6YmpHlRVpDjs8g8EThjta7DSkmCNEPfn0YU_Cx9cSphEQkuZdyu_C8rPGzMDqyziOu1yWZYfFxK5SVMsGjfIOeB4" +
//                    "qEDARssQ8-oREcp52Q4MmmT8d0oN4I4Fm_aa9X-R6dDkeWrFGrzcVEL1o"
//
//        const val TEST_JWT_TOKEN =
//            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJ0ZXN0IiwiY3JlYXRlZF9hdCI6MTY1NzAzOTc1ODM5MiwiZ" +
//                    "XhwaXJlc19hdCI6MTY4ODU3NTc1ODM5MiwiaWF0IjoxNjU3MDM5NzU4fQ.h-fkoLkMqphwMVeXIz9weXDYe3NgLrIIWYZcEGLD" +
//                    "0DOvjlRGhXVPte2E6o_IJCUWys4g3V4TbT9LCdVTAt9ay8850ivlfAEnL-rOt3gaKxZF0AOxfdBQCb6PODOkHULhEMuM3Cy9oL" +
//                    "g7BRu8dZFoMRdbwWslihsLk9bXDyPCcuNKjdcBr8n_fFfbGUrO0HdWIhGEj7qnujzFbPk2m4CbnMTcgHXxTYma8uaxpa03EU2p" +
//                    "fZbQiauIHATsmvUvtkoGCnR5W0Vpz7M-1qo1JNphEMfxrsa_-8mbCkjG7rEXpWFvWfW6FwObKT3xs5D8jRpAmX0kCyfe9lPZ2e" +
//                    "J-Rdhy95f6LpjJoXkaPLGVQpU_lsPUk8yb410s2dTkY47FzZojTtY6gfl3IHDXnkG8FcaV6qqbTpTyz16rN9CxAynrg-7QsYdr" +
//                    "cZpT8A-6cjHuCJabgFxqKNm7Jfknu_f34q-_rZJpVyeC_2AYDETEB3AwCiWoTAurTAhPF4ZoMbfkie75TTF3Hjhbimw7PnzHg2" +
//                    "WbvSQpHyTR96rII4rjpqtFwC49CmPOCJPxLpEpa5rMW3gbvKa-M1IJRSnKN0rsPF1Yr80LIl4x1w6JZp47_MoYPe0rWNPojnBY" +
//                    "KP-1-SRBc1skSsijxtOWiYoz2UbA9xIDWUrWh6g-wUFa-xE4eAjFKgE"
//
//        private val log = LoggerFactory.getLogger(ProcessorIntegrationTest::class.java)
//        val INSTANCE_ID = UUID.randomUUID().toString()
//        val vertx = Vertx.vertx()!!
//        lateinit var tcpSocket: NetSocket
//        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
//
//        @BeforeAll
//        @JvmStatic
//        fun setup() {
//            val platformPort = 5455
//            val useSsl = false
//            val trustAll = true
//            //val platformCertificateFile = "../../config/spp-platform.crt"
//            //val myCaAsABuffer = Buffer.buffer(File(platformCertificateFile).readText())
//            val options = when {
////                myCaAsABuffer != null -> NetClientOptions()
////                    .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
////                    .setSsl(true).setPemTrustOptions(PemTrustOptions().addCertValue(myCaAsABuffer))
//                useSsl -> NetClientOptions()
//                    .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
//                    .setSsl(true).setTrustAll(trustAll)
//                else -> NetClientOptions()
//                    .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
//            }
//
//            runBlocking {
//                withTimeout(5000) {
//                    tcpSocket = vertx.createNetClient(options).connect(platformPort, platformHost).await()
//                }
//                tcpSocket.handler(FrameParser(TCPServiceFrameParser(vertx, tcpSocket)))
//
//                //send marker connected status
//                val replyAddress = UUID.randomUUID().toString()
//                val pc = InstanceConnection(INSTANCE_ID, System.currentTimeMillis())
//                val consumer: MessageConsumer<Boolean> = vertx.eventBus().localConsumer(replyAddress)
//
//                val promise = Promise.promise<Void>()
//                consumer.handler {
//                    promise.complete()
//                    consumer.unregister()
//                }
//
//                FrameHelper.sendFrame(
//                    BridgeEventType.SEND.name.lowercase(), PlatformAddress.MARKER_CONNECTED,
//                    replyAddress, JsonObject(), true, JsonObject.mapFrom(pc), tcpSocket
//                )
//                withTimeout(5000) {
//                    promise.future().await()
//                }
//
//                vertx.eventBus().localConsumer<JsonObject>(SourceServices.Utilize.LIVE_INSTRUMENT) {
//                    setupHandler(it)
//                }
//                vertx.eventBus().localConsumer<JsonObject>(SourceServices.Utilize.LIVE_VIEW) {
//                    setupHandler(it)
//                }
//
//                //register listeners
//                FrameHelper.sendFrame(
//                    BridgeEventType.REGISTER.name.lowercase(),
//                    toLiveInstrumentSubscriberAddress("system"),
//                    JsonObject(), tcpSocket
//                )
//                FrameHelper.sendFrame(
//                    BridgeEventType.REGISTER.name.lowercase(),
//                    toLiveViewSubscriberAddress("system"),
//                    JsonObject(), tcpSocket
//                )
//            }
//        }
//
//        private fun setupHandler(resp: Message<JsonObject>) {
//            val forwardAddress = resp.address()
//            val forwardMessage = resp.body()
//            val replyAddress = UUID.randomUUID().toString()
//
//            if (log.isTraceEnabled) log.trace("Started listening at {}", replyAddress)
//            val tempConsumer = vertx.eventBus().localConsumer<Any>(replyAddress)
//            tempConsumer.handler {
//                resp.reply(it.body())
//                tempConsumer.unregister()
//
//                if (log.isTraceEnabled) log.trace("Finished listening at {}", replyAddress)
//            }
//
//            val headers = JsonObject()
//            resp.headers().entries().forEach { headers.put(it.key, it.value) }
//            FrameHelper.sendFrame(
//                BridgeEventType.SEND.name.lowercase(), forwardAddress,
//                replyAddress, headers, true, forwardMessage, tcpSocket
//            )
//        }
//    }
//}
