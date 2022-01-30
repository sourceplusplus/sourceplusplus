package spp.platform.core

import io.vertx.servicediscovery.impl.DefaultServiceDiscoveryBackend

//todo: shouldn't need this
class SourceServiceDiscovery : DefaultServiceDiscoveryBackend() {

    companion object {
        lateinit var INSTANCE: SourceServiceDiscovery
    }

    init {
        INSTANCE = this
    }
}
