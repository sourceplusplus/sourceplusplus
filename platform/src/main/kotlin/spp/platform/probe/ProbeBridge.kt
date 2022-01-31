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
import io.vertx.kotlin.coroutines.dispatcher
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
                    launch(vertx.dispatcher()) {
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
