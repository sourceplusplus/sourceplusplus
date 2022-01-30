package spp.platform.probe

import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.json.Json
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.core.SourceSubscriber
import spp.protocol.platform.PlatformAddress
import spp.protocol.probe.ProbeAddress
import spp.protocol.probe.status.ProbeConnection

class ProbeBridge(private val netServerOptions: NetServerOptions) : CoroutineVerticle() {

    private val log = LoggerFactory.getLogger(ProbeBridge::class.java)

    override suspend fun start() {
        log.debug("ProbeBridge started")
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                .addInboundPermitted(PermittedOptions().setAddressRegex("spp\\.platform\\.status\\..+"))
                .addInboundPermitted(PermittedOptions().setAddressRegex("spp\\.probe\\.status\\..+"))
                .addOutboundPermitted(PermittedOptions().setAddressRegex("spp\\.probe\\.command\\..+")),
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                if (it.rawMessage.getString("address") == PlatformAddress.PROBE_CONNECTED.address) {
                    val conn = Json.decodeValue(
                        it.rawMessage.getJsonObject("body").toString(), ProbeConnection::class.java
                    )
                    SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.probeId)

                    it.socket().closeHandler { _ ->
                        vertx.eventBus().publish(
                            PlatformAddress.PROBE_DISCONNECTED.address,
                            it.rawMessage.getJsonObject("body")
                        )
                    }
                }

                //auto-add probe id to headers
                val probeId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (probeId != null && it.rawMessage.containsKey("headers")) {
                    it.rawMessage.getJsonObject("headers").put("probe_id", probeId)
                }
            } else if (it.type() == BridgeEventType.REGISTERED) {
                val probeId = SourceSubscriber.getSubscriber(it.socket().writeHandlerID())
                if (probeId != null) {
                    launch {
                        delay(1500) //todo: this is temp fix for race condition
                        vertx.eventBus().publish(
                            ProbeAddress.REMOTE_REGISTERED.address,
                            it.rawMessage,
                            DeliveryOptions().addHeader("probe_id", probeId)
                        )
                    }
                } else {
                    log.error("Failed to register remote due to missing probe id")
                    it.fail("Missing probe id")
                    return@create
                }
            }
            it.complete(true)
        }.listen(config.getString("bridge_port").toInt()).await()
    }
}
