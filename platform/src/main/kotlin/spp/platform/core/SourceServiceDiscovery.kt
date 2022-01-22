package spp.platform.core

import io.vertx.servicediscovery.impl.DefaultServiceDiscoveryBackend

class SourceServiceDiscovery : DefaultServiceDiscoveryBackend() {

    companion object {
        lateinit var INSTANCE: SourceServiceDiscovery
    }

    init {
        INSTANCE = this
    }
}
