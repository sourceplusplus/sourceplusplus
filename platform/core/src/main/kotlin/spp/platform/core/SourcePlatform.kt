/*
 * Source++, the open-source live coding platform.
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

import com.google.common.base.CaseFormat
import com.google.common.io.Resources
import io.vertx.core.DeploymentOptions
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.PubSecKeyOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.ext.healthchecks.Status.KO
import io.vertx.ext.healthchecks.Status.OK
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.ext.web.handler.ResponseTimeHandler
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.LocalSessionStore
import io.vertx.ext.web.sstore.SessionStore
import io.vertx.ext.web.sstore.redis.RedisSessionStore
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.Status
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import spp.booster.PortalServer
import spp.platform.core.service.ServiceProvider
import spp.platform.bridge.marker.MarkerBridge
import spp.platform.bridge.probe.ProbeBridge
import spp.platform.bridge.probe.util.SelfSignedCertGenerator
import spp.platform.common.util.CertsToJksOptionsConverter
import spp.platform.storage.CoreStorage
import spp.platform.storage.MemoryStorage
import spp.platform.storage.RedisStorage
import spp.platform.storage.SourceStorage
import spp.protocol.SourceServices.Utilize
import spp.protocol.platform.ProbeAddress.LIVE_INSTRUMENT_REMOTE
import spp.protocol.service.LiveViewService
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

        lateinit var discovery: ServiceDiscovery

        fun addServiceCheck(checks: HealthChecks, serviceName: String) {
            val registeredName = "services/${serviceName.replace(".", "/")}"
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

    private fun setupDashboard(sessionHandler: SessionHandler, router: Router) {
        router.post("/auth").handler(sessionHandler).handler(BodyHandler.create()).handler {
            val postData = it.request().params()
            val password = postData.get("password")
            log.info { "Authenticating $password" }
            if (password?.isEmpty() == true) {
                it.redirect("/login")
                return@handler
            }

            val tenantId = postData.get("tenant_id")
            if (!tenantId.isNullOrEmpty()) {
                Vertx.currentContext().put("tenant_id", tenantId)
            }
            launch(vertx.dispatcher()) {
                val dev = SourceStorage.getDeveloperByAccessToken(password)
                if (dev != null) {
                    it.session().put("developer_id", dev.id)
                    it.redirect("/")
                } else {
                    if (tenantId != null) {
                        it.redirect("/login?tenant_id=$tenantId")
                    } else {
                        it.redirect("/login")
                    }
                }
            }
        }
        router.get("/login").handler(sessionHandler).handler {
            val loginHtml = Resources.toString(Resources.getResource("login.html"), Charsets.UTF_8)
            it.response().putHeader("Content-Type", "text/html").end(loginHtml)
        }
        router.get("/*").handler(sessionHandler).handler { ctx ->
            if (ctx.session().get<String>("developer_id") == null) {
                ctx.redirect("/login")
                return@handler
            } else {
                ctx.next()
            }
        }
        router.post("/graphql/dashboard").handler(sessionHandler).handler(BodyHandler.create()).handler { ctx ->
            if (ctx.session().get<String>("developer_id") != null) {
                val forward = JsonObject()
                forward.put("developer_id", ctx.session().get<String>("developer_id"))
                forward.put("body", ctx.body().asJsonObject())
                val headers = JsonObject()
                ctx.request().headers().names().forEach {
                    headers.put(it, ctx.request().headers().get(it))
                }
                forward.put("headers", headers)
                forward.put("method", ctx.request().method().name())
                vertx.eventBus().request<JsonObject>("skywalking-forwarder", forward) {
                    if (it.succeeded()) {
                        val resp = it.result().body()
                        ctx.response().setStatusCode(resp.getInteger("status")).end(resp.getString("body"))
                    } else {
                        log.error("Failed to forward SkyWalking request", it.cause())
                        ctx.response().setStatusCode(500).end(it.cause().message)
                    }
                }
            } else {
                ctx.response().setStatusCode(401).end("Unauthorized")
            }
        }

        //Serve dashboard
        PortalServer.addStaticHandler(router, sessionHandler)
        PortalServer.addSPAHandler(router, sessionHandler)
    }

    @Suppress("LongMethod")
    override suspend fun start() {
        log.info("Initializing Source++ Platform")

        val httpConfig = config.getJsonObject("spp-platform").getJsonObject("http")
        val sslEnabled = httpConfig.getString("ssl_enabled").toBooleanStrict()
        val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
        val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()

        val keyFile = File("config/spp-platform.key")
        val certFile = File("config/spp-platform.crt")
        if (!sslEnabled && !jwtEnabled) {
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

        val router = Router.router(vertx)
        router.route().handler(ResponseTimeHandler.create())
        router.errorHandler(500) {
            if (it.failed()) log.error("Failed request: " + it.request().path(), it.failure())
        }

        router["/api/new-token"].handler { ctx: RoutingContext ->
            if (!jwtEnabled) {
                log.debug("Skipped generating JWT token. Reason: JWT authentication disabled")
                ctx.response().setStatusCode(202).end()
                return@handler
            }
            val accessTokenParam = ctx.queryParam("access_token")
            if (accessTokenParam.isEmpty()) {
                log.warn("Invalid token request. Missing token.")
                ctx.response().setStatusCode(401).end()
                return@handler
            }

            val token = accessTokenParam[0]
            log.debug("Verifying access token: $token")
            launch(vertx.dispatcher()) {
                val dev = SourceStorage.getDeveloperByAccessToken(token)
                if (dev != null) {
                    val jwtToken = jwt!!.generateToken(
                        JsonObject()
                            .put("developer_id", dev.id)
                            .put("created_at", Instant.now().toEpochMilli())
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

        //Setup storage
        val sessionStore: SessionStore
        when (val storageSelector = config.getJsonObject("storage").getString("selector")) {
            "memory" -> {
                log.info("Using in-memory storage")
                SourceStorage.setup(MemoryStorage(vertx), config)
                sessionStore = LocalSessionStore.create(vertx)
            }
            "redis" -> {
                log.info("Using Redis storage")
                val redisStorage = RedisStorage()
                redisStorage.init(vertx, config.getJsonObject("storage").getJsonObject("redis"))

                val installDefaults = config.getJsonObject("storage").getJsonObject("redis")
                    .getString("install_defaults")?.toBooleanStrictOrNull() != false
                SourceStorage.setup(redisStorage, config, installDefaults)
                sessionStore = RedisSessionStore.create(vertx, redisStorage.redisClient)
            }
            else -> {
                try {
                    Class.forName(storageSelector, false, SourcePlatform::class.java.classLoader)
                    log.info("Using custom storage: $storageSelector")
                    val storageClass = Class.forName(storageSelector)
                    val customStorage = storageClass.getDeclaredConstructor().newInstance() as CoreStorage
                    val storageName = CaseFormat.LOWER_CAMEL.to(
                        CaseFormat.LOWER_HYPHEN,
                        storageClass.simpleName.removeSuffix("Storage")
                    )
                    customStorage.init(vertx, config.getJsonObject("storage").getJsonObject(storageName))

                    val installDefaults = config.getJsonObject("storage").getJsonObject(storageName)
                        .getString("install_defaults")?.toBooleanStrictOrNull() != false
                    SourceStorage.setup(customStorage, config, installDefaults)
                    sessionStore = LocalSessionStore.create(vertx) //todo: sessionStore
                } catch (e: ClassNotFoundException) {
                    log.error("Unknown storage selector: $storageSelector")
                    throw IllegalArgumentException("Unknown storage selector: $storageSelector")
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
            router.get("/clients").handler(jwtAuthHandler)
        } else {
            log.warn("JWT authentication disabled")
        }

        //S++ Graphql
        vertx.deployVerticle(SourceService(router), DeploymentOptions().setConfig(config.getJsonObject("spp-platform")))

        //Health checks
        val healthChecks = HealthChecks.create(vertx)
        addServiceCheck(healthChecks, Utilize.LIVE_SERVICE)
        router["/health"].handler(HealthCheckHandler.createWithHealthChecks(healthChecks))
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)

        //Open bridges
        val netServerOptions = NetServerOptions()
            .removeEnabledSecureTransportProtocol("SSLv2Hello")
            .removeEnabledSecureTransportProtocol("TLSv1")
            .removeEnabledSecureTransportProtocol("TLSv1.1")
            .setSsl(sslEnabled)
            .apply {
                if (sslEnabled) {
                    pemKeyCertOptions = PemKeyCertOptions()
                        .setKeyValue(Buffer.buffer(keyFile.readText()))
                        .setCertValue(Buffer.buffer(certFile.readText()))
                }
            }

        vertx.deployVerticle(
            ProbeBridge(router, jwt, netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform"))
        ).await()
        vertx.deployVerticle(
            MarkerBridge(jwt, netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("marker"))
        ).await()

        //Setup dashboard
        setupDashboard(SessionHandler.create(sessionStore), router)

        log.info("Starting service discovery")
        discovery = ServiceDiscovery.create(vertx)

        vertx.eventBus().consumer<JsonObject>(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS) {
            val record = Record(it.body())
            if (record.status == Status.UP) {
                launch(vertx.dispatcher()) {
                    vertx.sharedData().getLocalCounter(record.name).await().andIncrement.await()
                    log.debug { "Service UP: ${record.name}" }
                }
            } else if (record.status == Status.DOWN) {
                launch(vertx.dispatcher()) {
                    vertx.sharedData().getLocalCounter(record.name).await().decrementAndGet().await()
                    log.trace { "Service DOWN: ${record.name}" }
                }
            }
        }

        //todo: standardize
        vertx.eventBus().consumer<JsonObject>("get-records") {
            launch(vertx.dispatcher()) {
                val records = JsonArray(discovery.getRecords { true }.await().map { it.toJson() })
                it.reply(records)
                log.debug("Sent currently available services")
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

        if (sslEnabled) {
            log.debug("Starting HTTPS server(s)")
        } else {
            log.warn("TLS protocol disabled")
            log.debug("Starting HTTP server(s)")
        }
        val httpPorts = httpConfig.getString("port").split(",").map { it.toInt() }

        val httpOptions = HttpServerOptions().setSsl(sslEnabled)
        if (sslEnabled) {
            val jksOptions = CertsToJksOptionsConverter(certFile.absolutePath, keyFile.absolutePath).createJksOptions()
            httpOptions.setKeyStoreOptions(jksOptions)
        }
        httpPorts.forEach { httpPort ->
            val server = vertx.createHttpServer(httpOptions)
                .requestHandler(router)
                .listen(httpPort).await()
            if (sslEnabled) {
                log.info("HTTPS server started. Port: {}", server.actualPort())
            } else {
                log.info("HTTP server started. Port: {}", server.actualPort())
            }
        }

        if (sslEnabled && httpConfig.getString("redirect_to_https").toBooleanStrict()) {
            val redirectServer = vertx.createHttpServer().requestHandler {
                val redirectUrl = if (httpPorts.contains(443)) {
                    "https://${it.host()}${it.uri()}"
                } else {
                    "https://${it.host()}:${httpPorts.first()}${it.uri()}"
                }
                log.trace { "Redirecting HTTP to $redirectUrl" }
                it.response().putHeader("Location", redirectUrl).setStatusCode(302).end()
            }.listen(80).await()
            log.debug("HTTP redirect server started. Port: {}", redirectServer.actualPort())
        }
        log.debug("Source++ Platform initialized")
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
        log.debug("Get platform clients request. Developer: {}", selfId)

        launch(vertx.dispatcher()) {
            ctx.response().putHeader("Content-Type", "application/json")
                .end(
                    JsonObject()
                        .put("markers", JsonArray(Json.encode(MarkerBridge.getActiveMarkers(vertx))))
                        .put("probes", JsonArray(Json.encode(ProbeBridge.getActiveProbes(vertx))))
                        .toString()
                )
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
            val promise = Promise.promise<JsonObject>()
            EventBusService.getProxy(
                discovery, LiveViewService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveViewSubscriptionStats().onComplete {
                        if (it.succeeded()) {
                            promise.complete(it.result())
                        } else {
                            promise.fail(it.cause())
                        }
                    }
                } else {
                    promise.fail(it.cause())
                }
            }
            val subStats = try {
                promise.future().await()
            } catch (ex: Throwable) {
                log.error("Failed to get live view subscription stats", ex)
                ctx.response().setStatusCode(500).end()
                return@launch
            }

            ctx.response().putHeader("Content-Type", "application/json")
                .end(
                    JsonObject()
                        .put("platform", getPlatformStats())
                        .put("subscriptions", subStats)
                        .toString()
                )
        }
    }

    private suspend fun getPlatformStats(): JsonObject {
        return JsonObject()
            .put("connected-markers", MarkerBridge.getConnectedMarkerCount(vertx))
            .put("connected-probes", ProbeBridge.getConnectedProbeCount(vertx))
            .put(
                "services",
                JsonObject()
                    .put(
                        "core",
                        JsonObject()
                            .put(
                                Utilize.LIVE_SERVICE,
                                vertx.sharedData().getLocalCounter(Utilize.LIVE_SERVICE).await().get().await()
                            )
                            .put(
                                Utilize.LIVE_INSTRUMENT,
                                vertx.sharedData().getLocalCounter(Utilize.LIVE_INSTRUMENT).await().get().await()
                            )
                            .put(
                                Utilize.LIVE_VIEW,
                                vertx.sharedData().getLocalCounter(Utilize.LIVE_VIEW).await().get().await()
                            )
                    )
                    .put(
                        "probe",
                        JsonObject()
                            .put(
                                LIVE_INSTRUMENT_REMOTE,
                                vertx.sharedData().getLocalCounter(LIVE_INSTRUMENT_REMOTE)
                                    .await().get().await()
                            )
                    )
            )
    }
}
