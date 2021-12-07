package spp.platform.processor

import io.vertx.core.DeploymentOptions
import io.vertx.core.net.NetServerOptions
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await

class ProcessorVerticle(
    private val netServerOptions: NetServerOptions
) : CoroutineVerticle() {

    override suspend fun start() {
        //tracker
        vertx.deployVerticle(ProcessorTracker()).await()

        //bridge
        vertx.deployVerticle(
            ProcessorBridge(netServerOptions), DeploymentOptions().setConfig(config)
        ).await()
    }
}
