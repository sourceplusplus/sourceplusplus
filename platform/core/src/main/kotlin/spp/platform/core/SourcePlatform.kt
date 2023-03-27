/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.platform.core

import io.vertx.core.DeploymentOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.ext.web.handler.ResponseTimeHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import spp.platform.common.ClusterConnection
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.ClusterConnection.router
import spp.platform.common.util.CertsToJksOptionsConverter
import spp.platform.common.util.SelfSignedCertGenerator
import spp.platform.common.util.args
import spp.platform.core.api.GraphqlAPI
import spp.platform.core.api.RestAPI
import spp.platform.core.interceptors.SkyWalkingGraphqlInterceptor
import spp.platform.core.interceptors.SkyWalkingGrpcInterceptor
import spp.platform.core.service.ServiceProvider
import spp.platform.storage.SourceStorage
import java.io.File
import java.io.FileWriter
import java.io.StringReader
import java.io.StringWriter
import java.security.Security
import java.util.*
import javax.crypto.Cipher
import kotlin.system.exitProcess

/**
 * Deploys the core functionality of the Source++ platform.
 */
class SourcePlatform(private val manager: ModuleManager) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
        private val SPP_INSTANCE_ID = UUID.randomUUID().toString()

        init {
            Security.addProvider(BouncyCastleProvider())
            Security.setProperty("crypto.policy", "unlimited")
            val maxKeySize = Cipher.getMaxAllowedKeyLength("AES")
            if (maxKeySize != Int.MAX_VALUE) {
                System.err.println("Invalid max key size: $maxKeySize")
                exitProcess(-1)
            }
        }
    }

    @Suppress("LongMethod")
    override suspend fun start() {
        log.info("Initializing Source++ Platform")

        val httpConfig = config.getJsonObject("spp-platform").getJsonObject("http")
        val httpSslEnabled = httpConfig.getString("ssl_enabled").toBooleanStrict()
        val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
        val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()

        val keyFile = File("config/spp-platform.key")
        val certFile = File("config/spp-platform.crt")
        if (!httpSslEnabled && !jwtEnabled) {
            log.warn("Skipped generating security certificates")
        } else if (!keyFile.exists() || !certFile.exists()) {
            generateSecurityCertificates(keyFile, certFile)
        } else {
            log.info("Using existing security certificates")
        }

        val jwt: JWTAuth?
        if (!jwtEnabled) {
            jwt = null
        } else {
            val keyPair = PEMParser(StringReader(keyFile.readText())).use {
                JcaPEMKeyConverter().getKeyPair(it.readObject() as PEMKeyPair)
            }

            val publicKey = StringWriter()
            var writer = JcaPEMWriter(publicKey)
            writer.writeObject(keyPair.public)
            withContext(Dispatchers.IO) { writer.close() }
            val privateKey = StringWriter()
            writer = JcaPEMWriter(privateKey)
            writer.writeObject(keyPair.private)
            withContext(Dispatchers.IO) { writer.close() }

            jwt = JWTAuth.create(
                vertx, JWTAuthOptions()
                    .addPubSecKey(
                        PubSecKeyOptions()
                            .setAlgorithm("RS256")
                            .setBuffer(publicKey.toString())
                    )
                    .addPubSecKey(
                        PubSecKeyOptions()
                            .setAlgorithm("RS256")
                            .setBuffer(privateKey.toString())
                    )
            )
        }

        router.route().order(0).handler(ResponseTimeHandler.create())
        router.errorHandler(500) {
            if (it.failed()) log.error("Failed request: " + it.request().path(), it.failure())
        }

        //Setup JWT
        if (jwtEnabled) {
            val jwtAuthHandler = JWTAuthHandler.create(jwt)
            router.post("/graphql").order(1).handler(jwtAuthHandler)
            router.post("/graphql/skywalking").order(1).handler(jwtAuthHandler)
            router.post("/graphql/spp").order(1).handler(jwtAuthHandler)
            router.get("/health").order(1).handler(jwtAuthHandler)
            router.get("/stats").order(1).handler(jwtAuthHandler)
            router.get("/metrics").order(1).handler(jwtAuthHandler)
            router.get("/clients").order(1).handler(jwtAuthHandler)
        } else {
            log.warn("JWT authentication disabled")
        }

        //S++ APIs
        vertx.deployVerticle(RestAPI(jwtEnabled)).await()
        vertx.deployVerticle(GraphqlAPI(jwtEnabled)).await()

        //Service discovery
        vertx.eventBus().consumer<JsonObject>(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS) {
            val record = Record(it.body())
            if (record.status == Status.UP) {
                launch(vertx.dispatcher()) {
                    SourceStorage.counter(record.name).andIncrement.await()
                    log.debug { "Service UP: ${record.name}" }
                }
            } else if (record.status == Status.DOWN) {
                launch(vertx.dispatcher()) {
                    SourceStorage.counter(record.name).decrementAndGet().await()
                    log.trace { "Service DOWN: ${record.name}" }
                }
            }
        }

        //todo: standardize
        vertx.eventBus().consumer<JsonObject>("get-records") {
            launch(vertx.dispatcher()) {
                val records = JsonArray(discovery.getRecords { true }.await().map { it.toJson() })
                it.reply(records)
                log.debug { "Sent currently available services" }
                //todo: fix double reply (sm is sending two requests)
            }
        }

        //Start services
        vertx.deployVerticle(
            ServiceProvider(jwt, manager),
            DeploymentOptions().setConfig(config.put("SPP_INSTANCE_ID", SPP_INSTANCE_ID))
        ).await()

        //Add SkyWalking interceptors
        val grpcHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider().getService(GRPCHandlerRegister::class.java)
        grpcHandlerRegister.addFilter(SkyWalkingGrpcInterceptor(vertx, config))
        vertx.deployVerticle(SkyWalkingGraphqlInterceptor(router), DeploymentOptions().setConfig(config)).await()

        if (httpSslEnabled) {
            log.debug { "Starting HTTPS server(s)" }
        } else {
            log.warn("TLS protocol disabled")
            log.debug { "Starting HTTP server(s)" }
        }
        val httpPorts = httpConfig.getString("port").split(",").map { it.toInt() }

        val httpOptions = HttpServerOptions().setSsl(httpSslEnabled)
        if (httpSslEnabled) {
            val sslCertFile = File(
                httpConfig.getString("ssl_cert").orEmpty().ifEmpty { "config/spp-platform.crt" }
            )
            val sslKeyFile = File(
                httpConfig.getString("ssl_key").orEmpty().ifEmpty { "config/spp-platform.key" }
            )
            val jksOptions = CertsToJksOptionsConverter(
                sslCertFile.absolutePath, sslKeyFile.absolutePath
            ).createJksOptions()
            httpOptions.setKeyStoreOptions(jksOptions)
        }
        httpPorts.forEach { httpPort ->
            val httpServer = vertx.createHttpServer()
                .requestHandler(router)
                .listen(0)
            val server = ClusterConnection.multiUseNetServer.addUse(httpServer)
                .listen(httpOptions, httpPort).await()
            if (httpSslEnabled) {
                log.info("HTTPS server started. Port: {}", server.actualPort())
            } else {
                log.info("HTTP server started. Port: {}", server.actualPort())
            }
        }

        if (httpSslEnabled && httpConfig.getString("redirect_to_https").toBooleanStrict()) {
            val redirectServer = vertx.createHttpServer().requestHandler {
                val redirectUrl = if (httpPorts.contains(443)) {
                    "https://${it.host()}${it.uri()}"
                } else {
                    "https://${it.host()}:${httpPorts.first()}${it.uri()}"
                }
                log.trace { "Redirecting HTTP to $redirectUrl" }
                it.response().putHeader("Location", redirectUrl).setStatusCode(302).end()
            }.listen(80).await()
            log.debug { "HTTP redirect server started. Port: {}".args(redirectServer.actualPort()) }
        }
        log.debug { "Source++ Platform initialized" }
    }

    private fun generateSecurityCertificates(keyFile: File, certFile: File) {
        log.info("Generating security certificates")
        if (keyFile.exists()) keyFile.renameTo(
            File(keyFile.parentFile, keyFile.name + ".bak-" + System.nanoTime())
        )
        if (certFile.exists()) certFile.renameTo(
            File(certFile.parentFile, certFile.name + ".bak-" + System.nanoTime())
        )

        val keyPair = SelfSignedCertGenerator.generateKeyPair(4096)
        val certificate = SelfSignedCertGenerator.generate(
            keyPair, "SHA256WithRSAEncryption", "localhost", 365
        )

        keyFile.parentFile.mkdirs()
        val crt = FileWriter(certFile)
        var writer = JcaPEMWriter(crt)
        writer.writeObject(certificate)
        writer.close()
        val key = FileWriter(keyFile)
        writer = JcaPEMWriter(key)
        writer.writeObject(keyPair)
        writer.close()
        log.info("Security certificates generated")
    }
}
