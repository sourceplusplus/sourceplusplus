package spp.platform.core.service.live

import io.vertx.core.Vertx
import io.vertx.servicediscovery.ServiceDiscovery
import spp.platform.core.service.live.providers.LiveServiceProvider

class LiveProviders(
    vertx: Vertx,
    discovery: ServiceDiscovery
) {
    val liveService: LiveServiceProvider = LiveServiceProvider(vertx, discovery)
}
