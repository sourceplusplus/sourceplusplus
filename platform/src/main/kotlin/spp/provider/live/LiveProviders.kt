package spp.provider.live

import io.vertx.core.Vertx
import io.vertx.servicediscovery.ServiceDiscovery
import spp.provider.live.providers.LiveInstrumentProvider
import spp.provider.live.providers.LiveViewProvider

class LiveProviders(
    private val vertx: Vertx,
    private val discovery: ServiceDiscovery
) {
    val liveInstrument: LiveInstrumentProvider = LiveInstrumentProvider(vertx, discovery)
    val liveView: LiveViewProvider = LiveViewProvider(vertx, discovery)
}
