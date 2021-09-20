package spp.platform.processor

import io.vertx.core.DeploymentOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await

class ProcessorVerticle(
    private val jwtAuth: JWTAuth,
    private val sppTlsKey: String,
    private val sppTlsCert: String
) : CoroutineVerticle() {

    override suspend fun start() {
        //tracker
        vertx.deployVerticle(ProcessorTracker()).await()

        //bridge
        vertx.deployVerticle(
            ProcessorBridge(sppTlsKey, sppTlsCert), DeploymentOptions().setConfig(config)
        ).await()
    }
}
