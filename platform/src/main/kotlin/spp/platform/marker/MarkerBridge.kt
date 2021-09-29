package spp.platform.marker

import com.sourceplusplus.protocol.SourceMarkerServices.Provide
import com.sourceplusplus.protocol.SourceMarkerServices.Status
import com.sourceplusplus.protocol.SourceMarkerServices.Utilize
import com.sourceplusplus.protocol.status.MarkerConnection
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ClientAuth
import io.vertx.core.json.Json
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.core.SourceSubscriber
import spp.protocol.platform.PlatformAddress

class MarkerBridge(
    private val jwtAuth: JWTAuth,
    private val sppTlsKey: String,
    private val sppTlsCert: String
) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override suspend fun start() {
        log.debug("MarkerBridge started")
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from marker
                .addInboundPermitted(PermittedOptions().setAddress("get-records")) //todo: name like others
                .addInboundPermitted(PermittedOptions().setAddress(Status.MARKER_CONNECTED))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_VIEW))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LOG_COUNT_INDICATOR))
                //to marker
                .addOutboundPermitted(PermittedOptions().setAddress(Provide.LIVE_INSTRUMENT_SUBSCRIBER))
                .addOutboundPermitted(PermittedOptions().setAddress(Provide.LIVE_VIEW_SUBSCRIBER))
                .addOutboundPermitted(
                    PermittedOptions().setAddressRegex(Provide.LIVE_VIEW_SUBSCRIBER + "\\..+")
                ),
            NetServerOptions()
                .removeEnabledSecureTransportProtocol("SSLv2Hello")
                .removeEnabledSecureTransportProtocol("TLSv1")
                .removeEnabledSecureTransportProtocol("TLSv1.1")
                .setSsl(System.getenv("SPP_DISABLE_TLS") != "true").setClientAuth(ClientAuth.REQUEST)
                .setPemKeyCertOptions(
                    PemKeyCertOptions()
                        .setKeyValue(Buffer.buffer(sppTlsKey))
                        .setCertValue(Buffer.buffer(sppTlsCert))
                )
        ) {
            if (it.type() == BridgeEventType.SEND) {
                if (it.rawMessage.getString("address") == Status.MARKER_CONNECTED) {
                    GlobalScope.launch(vertx.dispatcher()) {
                        val conn = Json.decodeValue(
                            it.rawMessage.getJsonObject("body").toString(), MarkerConnection::class.java
                        )
                        SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.markerId)
                        it.socket().closeHandler { _ ->
                            vertx.eventBus().publish(
                                PlatformAddress.MARKER_DISCONNECTED.address,
                                it.rawMessage.getJsonObject("body")
                            )
                        }
                    }
                }

                //auto-add marker id to headers
                val markerId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (markerId != null && it.rawMessage.containsKey("headers")) {
                    it.rawMessage.getJsonObject("headers").put("marker_id", markerId)
                }
            }
            it.complete(true)
        }.listen(config.getInteger("bridge_port")).await()
    }
}
