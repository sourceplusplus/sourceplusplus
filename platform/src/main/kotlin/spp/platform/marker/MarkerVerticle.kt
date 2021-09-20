package spp.platform.marker

import io.vertx.core.DeploymentOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await

class MarkerVerticle(
    private val jwtAuth: JWTAuth,
    private val sppTlsKey: String,
    private val sppTlsCert: String
) : CoroutineVerticle() {

    override suspend fun start() {
        //tracker
        vertx.deployVerticle(MarkerTracker()).await()

        //bridge
        vertx.deployVerticle(
            MarkerBridge(jwtAuth, sppTlsKey, sppTlsCert), DeploymentOptions().setConfig(config)
        ).await()
    }
}
