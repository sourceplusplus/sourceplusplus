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
package spp.platform.marker

import io.vertx.core.net.NetServerOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import spp.protocol.SourceServices.Provide
import spp.protocol.SourceServices.Utilize
import spp.protocol.platform.PlatformAddress

class MarkerBridge(private val netServerOptions: NetServerOptions) : CoroutineVerticle() {

    override suspend fun start() {
        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from marker
                .addInboundPermitted(PermittedOptions().setAddress("get-records")) //todo: name like others
                .addInboundPermitted(PermittedOptions().setAddress(PlatformAddress.MARKER_CONNECTED.address))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_SERVICE))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_VIEW))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LOG_COUNT_INDICATOR))
                //to marker
                .addOutboundPermitted(PermittedOptions().setAddress(Provide.LIVE_INSTRUMENT_SUBSCRIBER))
                .addOutboundPermitted(
                    PermittedOptions().setAddressRegex(Provide.LIVE_VIEW_SUBSCRIBER + "\\..+")
                ),
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                if (it.rawMessage.getString("address") == PlatformAddress.MARKER_CONNECTED.address) {
                    launch(vertx.dispatcher()) {
                        it.socket().closeHandler { _ ->
                            vertx.eventBus().publish(
                                PlatformAddress.MARKER_DISCONNECTED.address,
                                it.rawMessage.getJsonObject("body")
                            )
                        }
                    }
                }
            }
            it.complete(true)
        }.listen(config.getString("bridge_port").toInt()).await()
    }
}
