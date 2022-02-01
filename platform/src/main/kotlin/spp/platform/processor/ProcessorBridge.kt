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
package spp.platform.processor

import io.vertx.core.json.Json
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import org.slf4j.LoggerFactory
import spp.platform.SourcePlatform.Companion.addServiceCheck
import spp.platform.core.SourceSubscriber
import spp.protocol.SourceMarkerServices
import spp.protocol.SourceMarkerServices.Utilize
import spp.protocol.platform.PlatformAddress
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.probe.ProbeAddress
import spp.protocol.processor.ProcessorAddress.SET_LOG_PUBLISH_RATE_LIMIT
import spp.protocol.status.InstanceConnection

class ProcessorBridge(
    private val healthChecks: HealthChecks,
    private val netServerOptions: NetServerOptions
) : CoroutineVerticle() {

    private val log = LoggerFactory.getLogger(ProcessorBridge::class.java)

    override suspend fun start() {
        val liveInstrumentEnabled = config.getJsonObject("live-instrument")?.getString("enabled")?.toBoolean() ?: false
        log.debug("Live instrument processor ${if (liveInstrumentEnabled) "enabled" else "disabled"}")
        if (liveInstrumentEnabled) addServiceCheck(healthChecks, Utilize.LIVE_INSTRUMENT)
        val liveViewEnabled = config.getJsonObject("live-view")?.getString("enabled")?.toBoolean() ?: false
        log.debug("Live view processor ${if (liveViewEnabled) "enabled" else "disabled"}")
        if (liveViewEnabled) addServiceCheck(healthChecks, Utilize.LIVE_VIEW)

        TcpEventBusBridge.create(
            vertx,
            BridgeOptions()
                //from processor
                .addInboundPermitted(PermittedOptions().setAddress(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS))
                .addInboundPermitted(PermittedOptions().setAddress(ServiceDiscoveryOptions.DEFAULT_USAGE_ADDRESS))
                .addInboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_SERVICE))
                .addInboundPermitted(PermittedOptions().setAddress(PlatformAddress.PROCESSOR_CONNECTED.address))
                .apply { if (liveInstrumentEnabled) addLiveInstrumentInbound() }
                .apply { if (liveViewEnabled) addLiveViewInbound() }
                //to processor
                .addOutboundPermitted(PermittedOptions().setAddress(ProbeAddress.REMOTE_REGISTERED.address))
                .addOutboundPermitted(PermittedOptions().setAddress(MARKER_DISCONNECTED.address))
                .apply { if (liveInstrumentEnabled) addLiveInstrumentOutbound() }
                .apply { if (liveViewEnabled) addLiveViewOutbound() },
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                val address = it.rawMessage.getString("address")
                if (address == PlatformAddress.PROCESSOR_CONNECTED.address) {
                    val conn = Json.decodeValue(
                        it.rawMessage.getJsonObject("body").toString(), InstanceConnection::class.java
                    )
                    SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.instanceId)

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
        }.listen(config.getString("bridge_port").toInt()).await()
    }

    private fun BridgeOptions.addLiveViewOutbound() {
        addOutboundPermitted(PermittedOptions().setAddress(Utilize.LOG_COUNT_INDICATOR))
            .addOutboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_VIEW))
            .addOutboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT))
    }

    private fun BridgeOptions.addLiveInstrumentOutbound() {
        addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_BREAKPOINT_APPLIED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_BREAKPOINT_REMOVED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_LOG_APPLIED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_LOG_REMOVED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_METER_APPLIED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_METER_REMOVED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_SPAN_APPLIED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(PlatformAddress.LIVE_SPAN_REMOVED.address))
            .addOutboundPermitted(PermittedOptions().setAddress(SET_LOG_PUBLISH_RATE_LIMIT.address))
    }

    private fun BridgeOptions.addLiveViewInbound() {
        addInboundPermitted(
            PermittedOptions().setAddressRegex(SourceMarkerServices.Provide.LIVE_VIEW_SUBSCRIBER + "\\..+")
        )
    }

    private fun BridgeOptions.addLiveInstrumentInbound() {
        addInboundPermitted(PermittedOptions().setAddress(SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER))
            .addInboundPermitted(
                PermittedOptions().setAddressRegex(SourceMarkerServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER + "\\..+")
            )
            .addInboundPermitted(
                PermittedOptions().setAddressRegex(ProbeAddress.LIVE_BREAKPOINT_REMOTE.address + "\\:.+")
            )
            .addInboundPermitted(
                PermittedOptions().setAddressRegex(ProbeAddress.LIVE_LOG_REMOTE.address + "\\:.+")
            )
            .addInboundPermitted(
                PermittedOptions().setAddressRegex(ProbeAddress.LIVE_METER_REMOTE.address + "\\:.+")
            )
            .addInboundPermitted(
                PermittedOptions().setAddressRegex(ProbeAddress.LIVE_SPAN_REMOTE.address + "\\:.+")
            )
    }
}
