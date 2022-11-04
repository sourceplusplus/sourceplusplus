/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022 CodeBrig, Inc.
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
import io.vertx.core.Vertx
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.dropwizard.MetricsService
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.ext.healthchecks.Status.KO
import io.vertx.ext.healthchecks.Status.OK
import io.vertx.ext.web.RoutingContext
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
import spp.platform.core.service.ServiceProvider
import spp.platform.storage.SourceStorage
import spp.protocol.service.LiveManagementService
import spp.protocol.service.SourceServices.LIVE_INSTRUMENT
import spp.protocol.service.SourceServices.LIVE_MANAGEMENT_SERVICE
import spp.protocol.service.SourceServices.LIVE_VIEW
import java.io.File
import java.io.FileWriter
import java.io.StringReader
import java.io.StringWriter
import java.security.Security
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.Cipher
import kotlin.system.exitProcess

class SourcePlatform : CoroutineVerticle() {

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

        fun addServiceCheck(checks: HealthChecks, serviceName: String) {
            val registeredName = "services/${serviceName.substringAfterLast(".")}"
            checks.register(registeredName) { promise ->
                discovery.getRecord({ rec -> serviceName == rec.name }
                ) { record ->
                    when {
                        record.failed() -> promise.fail(record.cause())
                        record.result() == null -> {
                            val debugData = JsonObject().put("reason", "No published record(s)")
                            promise.complete(KO(debugData))
                        }

                        else -> {
                            val reference = discovery.getReference(record.result())
                            try {
                                reference.get<Any>()
                                promise.complete(OK())
                            } catch (ex: Throwable) {
                                ex.printStackTrace()
                                val debugData = JsonObject().put("reason", ex.message)
                                    .put("stack_trace", ex.stackTraceToString())
                                promise.complete(KO(debugData))
                            } finally {
                                reference.release()
                            }
                        }
                    }
                }
            }
        }
    }

    @Suppress("LongMethod")
    override suspend fun start() {
        log.info("Initializing Source++ Platform")

        val httpConfig = config.getJsonObject("spp-platform").getJsonObject("http")
        val httpSslEnabled = httpConfig.getString("ssl_enabled").toBooleanStrict()
        val grpcConfig = config.getJsonObject("spp-platform").getJsonObject("grpc")
        val grpcSslEnabled = grpcConfig.getString("ssl_enabled").toBooleanStrict()
        val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
        val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()

        val keyFile = File("config/spp-platform.key")
        val certFile = File("config/spp-platform.crt")
        if (!httpSslEnabled && !grpcSslEnabled && !jwtEnabled) {
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

        router.route().handler(ResponseTimeHandler.create())
        router.errorHandler(500) {
            if (it.failed()) log.error("Failed request: " + it.request().path(), it.failure())
        }

        router["/api/new-token"].handler { ctx: RoutingContext ->
            if (!jwtEnabled) {
                log.debug { "Skipped generating JWT token. Reason: JWT authentication disabled" }
                ctx.response().setStatusCode(202).end()
                return@handler
            }
            val accessTokenParam = ctx.queryParam("access_token")
            if (accessTokenParam.isEmpty()) {
                log.warn("Invalid token request. Missing token.")
                ctx.response().setStatusCode(401).end()
                return@handler
            }
            val tenantId = ctx.queryParam("tenant_id").firstOrNull()
            if (!tenantId.isNullOrEmpty()) {
                Vertx.currentContext().putLocal("tenant_id", tenantId)
            } else {
                Vertx.currentContext().removeLocal("tenant_id")
            }

            val token = accessTokenParam[0]
            log.debug { "Verifying access token: {}".args(token) }
            launch(vertx.dispatcher()) {
                val dev = SourceStorage.getDeveloperByAccessToken(token)
                if (dev != null) {
                    val jwtToken = jwt!!.generateToken(
                        JsonObject()
                            .apply {
                                if (!tenantId.isNullOrEmpty()) {
                                    put("tenant_id", tenantId)
                                }
                            }
                            .put("developer_id", dev.id)
                            .put("created_at", Instant.now().toEpochMilli())
                            //todo: reasonable expires_at
                            .put("expires_at", Instant.now().plus(365, ChronoUnit.DAYS).toEpochMilli()),
                        JWTOptions().setAlgorithm("RS256")
                    )
                    ctx.end(jwtToken)
                } else {
                    log.warn("Invalid token request. Token: {}", token)
                    ctx.response().setStatusCode(401).end()
                }
            }
        }

        //Setup JWT
        if (jwtEnabled) {
            val jwtAuthHandler = JWTAuthHandler.create(jwt)
            router.post("/graphql").handler(jwtAuthHandler)
            router.post("/graphql/skywalking").handler(jwtAuthHandler)
            router.post("/graphql/spp").handler(jwtAuthHandler)
            router.get("/health").handler(jwtAuthHandler)
            router.get("/stats").handler(jwtAuthHandler)
            router.get("/metrics").handler(jwtAuthHandler)
            router.get("/clients").handler(jwtAuthHandler)
        } else {
            log.warn("JWT authentication disabled")
        }

        //S++ Graphql
        vertx.deployVerticle(SourceService(router), DeploymentOptions().setConfig(config.getJsonObject("spp-platform")))

        //Health checks
        val healthChecks = HealthChecks.create(vertx)
        addServiceCheck(healthChecks, LIVE_MANAGEMENT_SERVICE)
        addServiceCheck(healthChecks, LIVE_INSTRUMENT)
        addServiceCheck(healthChecks, LIVE_VIEW)
        router["/health"].handler(HealthCheckHandler.createWithHealthChecks(healthChecks))
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)

        //Internal metrics
        val metricsService = MetricsService.create(vertx)
        router["/metrics"].handler {

            if (it.queryParam("include_unused").contains("true")) {
                val vertxMetrics = metricsService.getMetricsSnapshot(vertx)
                it.end(vertxMetrics.encodePrettily())
            } else {
                val rtnMetrics = JsonObject()
                val vertxMetrics = metricsService.getMetricsSnapshot(vertx)
                vertxMetrics.fieldNames().forEach {
                    val metric = vertxMetrics.getJsonObject(it)
                    val allZeros = metric.fieldNames().all {
                        if (metric.getValue(it) is Number && (metric.getValue(it) as Number).toDouble() == 0.0) {
                            true
                        } else metric.getValue(it) !is Number
                    }
                    if (!allZeros) {
                        rtnMetrics.put(it, metric)
                    }
                }
                it.end(rtnMetrics.encodePrettily())
            }
        }

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
            ServiceProvider(jwt), DeploymentOptions().setConfig(config.put("SPP_INSTANCE_ID", SPP_INSTANCE_ID))
        ).await()

        //Start SkyWalking proxy
        vertx.deployVerticle(
            SkyWalkingInterceptor(router), DeploymentOptions().setConfig(config)
        ).await()

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
            val jksOptions = CertsToJksOptionsConverter(sslCertFile.absolutePath, sslKeyFile.absolutePath).createJksOptions()
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

    private fun getClients(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
            val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.debug { "Get platform clients request. Developer: {}".args(selfId) }

        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx, accessToken).getClients().onSuccess {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(it.toString())
            }.onFailure {
                if (it is ReplyException) {
                    log.error("Failed to get platform clients. Reason: {}", it.message)
                    if (it.failureCode() < 0) {
                        ctx.response().setStatusCode(500).end(it.message)
                    } else {
                        ctx.response().setStatusCode(it.failureCode()).end(it.message)
                    }
                } else {
                    log.error("Failed to get platform clients", it)
                    ctx.response().setStatusCode(500).end()
                }
            }
        }
    }

    private fun getStats(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
            val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info("Get platform stats request. Developer: {}", selfId)

        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx, accessToken).getStats().onSuccess {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(it.toString())
            }.onFailure {
                if (it is ReplyException) {
                    log.error("Failed to get platform stats. Reason: {}", it.message)
                    if (it.failureCode() < 0) {
                        ctx.response().setStatusCode(500).end(it.message)
                    } else {
                        ctx.response().setStatusCode(it.failureCode()).end(it.message)
                    }
                } else {
                    log.error("Failed to get platform stats", it)
                    ctx.response().setStatusCode(500).end()
                }
            }
        }
    }
}
