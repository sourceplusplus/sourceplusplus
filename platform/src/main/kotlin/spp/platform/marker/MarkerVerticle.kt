package spp.platform.marker

import io.vertx.core.DeploymentOptions
import io.vertx.core.net.NetServerOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await

class MarkerVerticle(
    private val jwtAuth: JWTAuth?,
    private val netServerOptions: NetServerOptions
) : CoroutineVerticle() {

    override suspend fun start() {
        //tracker
        vertx.deployVerticle(MarkerTracker(jwtAuth)).await()

        //bridge
        vertx.deployVerticle(
            MarkerBridge(netServerOptions), DeploymentOptions().setConfig(config)
        ).await()
    }
}
