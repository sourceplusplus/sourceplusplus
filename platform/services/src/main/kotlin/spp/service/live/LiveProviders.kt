package spp.service.live

import io.vertx.core.Vertx
import io.vertx.servicediscovery.ServiceDiscovery
import spp.service.live.providers.LiveInstrumentProvider
import spp.service.live.providers.LiveServiceProvider

class LiveProviders(
    vertx: Vertx,
    discovery: ServiceDiscovery
) {
    val liveService: LiveServiceProvider = LiveServiceProvider(vertx, discovery)
    val liveInstrument: LiveInstrumentProvider = LiveInstrumentProvider(vertx, discovery)
}
