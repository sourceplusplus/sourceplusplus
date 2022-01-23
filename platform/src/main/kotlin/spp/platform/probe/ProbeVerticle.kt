package spp.platform.probe

import io.vertx.core.DeploymentOptions
import io.vertx.core.net.NetServerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await

class ProbeVerticle(
    private val netServerOptions: NetServerOptions
) : CoroutineVerticle() {

    override suspend fun start() {
        //tracker
        vertx.deployVerticle(ProbeTracker()).await()

        //bridge
        vertx.deployVerticle(
            ProbeBridge(netServerOptions), DeploymentOptions().setConfig(config)
        ).await()

        //functionality
        vertx.deployVerticle(ProbeGenerator()).await()
    }
}
