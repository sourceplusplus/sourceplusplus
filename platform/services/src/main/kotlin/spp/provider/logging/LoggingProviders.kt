package spp.provider.logging

import io.vertx.core.Vertx
import io.vertx.servicediscovery.ServiceDiscovery
import spp.provider.logging.providers.LogCountIndicator

class LoggingProviders(
    private val vertx: Vertx,
    private val discovery: ServiceDiscovery
) {
    val logCountIndicator: LogCountIndicator = LogCountIndicator(discovery)
}
