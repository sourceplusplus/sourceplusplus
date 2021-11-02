package spp.platform.probe

import io.vertx.core.DeploymentOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await

class ProbeVerticle(
    private val sppTlsKey: String,
    private val sppTlsCert: String
) : CoroutineVerticle() {

    override suspend fun start() {
        //tracker
        vertx.deployVerticle(ProbeTracker()).await()

        //bridge
        vertx.deployVerticle(
            ProbeBridge(sppTlsKey, sppTlsCert), DeploymentOptions().setConfig(config)
        ).await()

        //functionality
        vertx.deployVerticle(ProbeGenerator()).await()
    }
}
