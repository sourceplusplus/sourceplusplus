package spp.platform.processor

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ClientAuth
import io.vertx.core.json.Json
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import org.slf4j.LoggerFactory
import spp.platform.core.SourceSubscriber
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.processor.ProcessorAddress.*
import spp.protocol.processor.status.ProcessorConnection

class ProcessorBridge(private val sppTlsKey: String, private val sppTlsCert: String) : CoroutineVerticle() {

    private val log = LoggerFactory.getLogger(ProcessorBridge::class.java)

    override suspend fun start() {
        log.debug("ProcessorBridge started")
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from processor
                .addInboundPermitted(PermittedOptions().setAddress(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS))
                .addInboundPermitted(PermittedOptions().setAddress(ServiceDiscoveryOptions.DEFAULT_USAGE_ADDRESS))
                .addInboundPermitted(PermittedOptions().setAddress(PlatformAddress.PROCESSOR_CONNECTED.address))
                .addInboundPermitted(PermittedOptions().setAddress(BREAKPOINT_HIT.address))
                .addInboundPermitted(PermittedOptions().setAddress(LOG_HIT.address))
                .addInboundPermitted(PermittedOptions().setAddress(VIEW_SUBSCRIPTION_EVENT.address))
                //to processor
                .addOutboundPermitted(PermittedOptions().setAddress(MARKER_DISCONNECTED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(LOGGING_PROCESSOR.address))
                .addOutboundPermitted(PermittedOptions().setAddress(LIVE_VIEW_PROCESSOR.address))
                .addOutboundPermitted(PermittedOptions().setAddress(LIVE_INSTRUMENT_PROCESSOR.address))
                .addOutboundPermitted(PermittedOptions().setAddress(SET_LOG_PUBLISH_RATE_LIMIT.address)),
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
                val address = it.rawMessage.getString("address")
                if (address == PlatformAddress.PROCESSOR_CONNECTED.address) {
                    val conn = Json.decodeValue(
                        it.rawMessage.getJsonObject("body").toString(), ProcessorConnection::class.java
                    )
                    SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.processorId)

                    it.socket().closeHandler { _ ->
                        vertx.eventBus().publish(
                            PlatformAddress.PROCESSOR_DISCONNECTED.address,
                            it.rawMessage.getJsonObject("body")
                        )
                    }
                }

                //auto-add processor id to headers
                val processorId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (processorId != null && it.rawMessage.containsKey("headers")) {
                    it.rawMessage.getJsonObject("headers").put("processor_id", processorId)
                }
            }
            it.complete(true)
        }.listen(config.getInteger("bridge_port")).await()
    }

    override suspend fun stop() {
        log.debug("ProcessorBridge stopped")
    }
}
