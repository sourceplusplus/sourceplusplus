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

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.bridge.BridgeOptions
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.SourcePlatform
import spp.platform.SourcePlatform.Companion.addServiceCheck
import spp.platform.core.InstanceBridge
import spp.platform.core.SourceSubscriber
import spp.protocol.SourceServices
import spp.protocol.SourceServices.Utilize
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.platform.PlatformAddress.PROCESSOR_CONNECTED
import spp.protocol.platform.PlatformAddress.PROCESSOR_DISCONNECTED
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.ProcessorAddress.LIVE_INSTRUMENT_APPLIED
import spp.protocol.platform.ProcessorAddress.LIVE_INSTRUMENT_REMOVED
import spp.protocol.platform.ProcessorAddress.REMOTE_REGISTERED
import spp.protocol.platform.ProcessorAddress.SET_LOG_PUBLISH_RATE_LIMIT
import spp.protocol.platform.status.ActiveInstance
import spp.protocol.platform.status.InstanceConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProcessorBridge(
    private val healthChecks: HealthChecks,
    jwtAuth: JWTAuth?,
    private val netServerOptions: NetServerOptions
) : InstanceBridge(jwtAuth) {

    companion object {
        private val log = KotlinLogging.logger {}

        private const val connectedProcessorsAddress = "get-connected-processors"
        private const val activeProcessorsAddress = "get-active-processors"

        suspend fun getConnectedProcessorCount(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedProcessorsAddress, null).await().body()
        }

        suspend fun getActiveProcessors(vertx: Vertx): List<ActiveInstance> {
            return vertx.eventBus().request<List<ActiveInstance>>(activeProcessorsAddress, null).await().body()
        }
    }

    private val activeProcessors: MutableMap<String, ActiveInstance> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(activeProcessorsAddress) {
            launch(vertx.dispatcher()) {
                it.reply(ArrayList(activeProcessors.values))
            }
        }
        vertx.eventBus().consumer<JsonObject>(connectedProcessorsAddress) {
            launch(vertx.dispatcher()) {
                it.reply(vertx.sharedData().getLocalCounter(PROCESSOR_CONNECTED).await().get().await())
            }
        }
        vertx.eventBus().consumer<JsonObject>(PROCESSOR_CONNECTED) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { "Establishing connection with processor ${conn.instanceId}" }

            activeProcessors[conn.instanceId] = ActiveInstance(conn.instanceId, System.currentTimeMillis(), conn.meta)
            it.reply(true)
            log.info("Processor connected. Latency: {}ms - Active processors: {}", latency, activeProcessors.size)

            launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PROCESSOR_CONNECTED).await().incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PROCESSOR_DISCONNECTED) {
            val conn = Json.decodeValue(it.body().toString(), InstanceConnection::class.java)
            val connectedAt = Instant.ofEpochMilli(activeProcessors.remove(conn.instanceId)!!.connectedAt)
            log.info("Processor disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

            launch(vertx.dispatcher()) {
                SourcePlatform.discovery.getRecords { true }.await().forEach {
                    if (it.metadata.getString("INSTANCE_ID") == conn.instanceId) {
                        SourcePlatform.discovery.unpublish(it.registration)
                    }
                }

                vertx.sharedData().getLocalCounter(PROCESSOR_CONNECTED).await().decrementAndGet().await()
            }
        }

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
                .addInboundPermitted(PermittedOptions().setAddress(PROCESSOR_CONNECTED))
                .apply { if (liveInstrumentEnabled) addLiveInstrumentInbound() }
                .apply { if (liveViewEnabled) addLiveViewInbound() }
                //to processor
                .addOutboundPermitted(PermittedOptions().setAddress(REMOTE_REGISTERED))
                .addOutboundPermitted(PermittedOptions().setAddress(MARKER_DISCONNECTED))
                .apply { if (liveInstrumentEnabled) addLiveInstrumentOutbound() }
                .apply { if (liveViewEnabled) addLiveViewOutbound() },
            netServerOptions
        ) {
            if (it.type() == BridgeEventType.SEND) {
                val address = it.rawMessage.getString("address")
                if (address == PROCESSOR_CONNECTED) {
                    val conn = Json.decodeValue(
                        it.rawMessage.getJsonObject("body").toString(), InstanceConnection::class.java
                    )
                    SourceSubscriber.addSubscriber(it.socket().writeHandlerID(), conn.instanceId)

                    it.socket().closeHandler { _ ->
                        vertx.eventBus().publish(
                            PROCESSOR_DISCONNECTED,
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
            it.complete(true) //todo: validateAuth(it)
        }.listen(config.getString("bridge_port").toInt()).await()
    }

    private fun BridgeOptions.addLiveViewOutbound() {
        addOutboundPermitted(PermittedOptions().setAddress(Utilize.LOG_COUNT_INDICATOR))
            .addOutboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_VIEW))
            .addOutboundPermitted(PermittedOptions().setAddress(Utilize.LIVE_INSTRUMENT))
    }

    private fun BridgeOptions.addLiveInstrumentOutbound() {
        addOutboundPermitted(PermittedOptions().setAddress(LIVE_INSTRUMENT_APPLIED))
            .addOutboundPermitted(PermittedOptions().setAddress(LIVE_INSTRUMENT_REMOVED))
            .addOutboundPermitted(PermittedOptions().setAddress(SET_LOG_PUBLISH_RATE_LIMIT))
    }

    private fun BridgeOptions.addLiveViewInbound() {
        addInboundPermitted(
            PermittedOptions().setAddressRegex(SourceServices.Provide.LIVE_VIEW_SUBSCRIBER + "\\:.+")
        )
    }

    private fun BridgeOptions.addLiveInstrumentInbound() {
        addInboundPermitted(
            PermittedOptions().setAddressRegex(SourceServices.Provide.LIVE_INSTRUMENT_SUBSCRIBER + "\\:.+")
        ).addInboundPermitted(
            PermittedOptions().setAddressRegex(ProbeAddress.LIVE_INSTRUMENT_REMOTE)
        ).addInboundPermitted(
            PermittedOptions().setAddressRegex(ProbeAddress.LIVE_INSTRUMENT_REMOTE + "\\:.+")
        )
    }
}
