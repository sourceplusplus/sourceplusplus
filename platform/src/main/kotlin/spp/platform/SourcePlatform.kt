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
package spp.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
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
import io.vertx.ext.web.handler.graphql.GraphQLHandler
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
import io.vertx.servicediscovery.impl.DefaultServiceDiscoveryBackend
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.lookup.StringLookupFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.booster.PortalServer
import spp.platform.core.SkyWalkingInterceptor
import spp.platform.core.SourceService
import spp.platform.core.SourceStorage
import spp.platform.core.service.ServiceProvider
import spp.platform.core.storage.MemoryStorage
import spp.platform.core.storage.RedisStorage
import spp.platform.core.util.CertsToJksOptionsConverter
import spp.platform.marker.MarkerBridge
import spp.platform.probe.ProbeBridge
import spp.platform.probe.util.SelfSignedCertGenerator
import spp.platform.processor.ProcessorBridge
import spp.protocol.SourceServices.Utilize
import spp.protocol.marshall.LocalMessageCodec
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
import kotlin.collections.set
import kotlin.system.exitProcess

class SourcePlatform : CoroutineVerticle() {

    companion object {
        private var USE_DEFAULT_LOGGING_CONFIGURATION = true

        init {
            if (File("config/logback.xml").exists()) {
                USE_DEFAULT_LOGGING_CONFIGURATION = false
                System.setProperty("logback.configurationFile", File("config/logback.xml").absoluteFile.absolutePath)
                val context = LoggerFactory.getILoggerFactory() as LoggerContext
                try {
                    val configurator = ch.qos.logback.classic.joran.JoranConfigurator()
                    configurator.context = context
                    context.reset()
                    configurator.doConfigure(File("config/logback.xml"))
                } catch (ex: ch.qos.logback.core.joran.spi.JoranException) {
                    ex.printStackTrace()
                }
                LoggerFactory.getLogger(SourcePlatform::class.java)
                    .trace("Set logging via {}", File("config/logback.xml"))
            } else {
                LoggerFactory.getLogger(SourcePlatform::class.java).trace("Using default logging configuration")
            }
        }

        private val log = KotlinLogging.logger {}
        private val SPP_INSTANCE_ID = UUID.randomUUID().toString()
        private val BUILD = ResourceBundle.getBundle("build")

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

        @JvmStatic
        fun main(args: Array<String>) {
            val sppConfig = JsonObject(
                StringSubstitutor(StringLookupFactory.INSTANCE.environmentVariableStringLookup()).replace(
                    ObjectMapper().writeValueAsString(
                        YAMLMapper().readValue(File("config/spp-platform.yml"), Object::class.java)
                    )
                )
            )

            if (USE_DEFAULT_LOGGING_CONFIGURATION) {
                val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
                loggerContext.loggerList.filter { it.name.startsWith("spp") }.forEach {
                    it.level = Level.toLevel(
                        sppConfig.getJsonObject("spp-platform").getJsonObject("logging").getString("level")
                    )
                }
            }
            log.info("Booting Source++ Platform [v${BUILD.getString("build_version")}]")
            log.trace { "Build id: " + BUILD.getString("build_id") }
            log.trace { "Build date: " + BUILD.getString("build_date") }

            runBlocking {
                try {
                    val vertx = Vertx.vertx()
                    vertx.eventBus().registerDefaultCodec(ArrayList::class.java, LocalMessageCodec())

                    vertx.deployVerticle(SourcePlatform(), DeploymentOptions().setConfig(sppConfig)).await()
                } catch (ex: Throwable) {
                    ex.printStackTrace()
                    exitProcess(-1)
                }
            }
        }

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

    private fun setupDashboard(router: Router) {
        router.post("/auth").handler(BodyHandler.create()).handler {
            val postData = it.request().params()
            val password = postData.get("password")
            log.info { "Authenticating $password" }
            if (password?.isEmpty() == true) {
                it.redirect("/login")
                return@handler
            }

            launch(vertx.dispatcher()) {
                val dev = SourceStorage.getDeveloperByAccessToken(password)
                if (dev != null) {
                    it.session().put("developer_id", dev.id)
                    it.redirect("/")
                } else {
                    it.redirect("/login")
                }
            }
        }
        router.get("/login").handler {
            val loginHtml = Resources.toString(Resources.getResource("login.html"), Charsets.UTF_8)
            it.response().putHeader("Content-Type", "text/html").end(loginHtml)
        }
        router.get("/*").handler { ctx ->
            if (ctx.session().get<String>("developer_id") == null) {
                ctx.redirect("/login")
                return@handler
            } else {
                ctx.next()
            }
        }
        router.post("/graphql").handler(BodyHandler.create()).handler { ctx ->
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
                ctx.next()
            }
        }

        PortalServer.addStaticHandler(router)
        PortalServer.addSPAHandler(router)
    }

    @Suppress("LongMethod")
    override suspend fun start() {
        log.info("Initializing Source++ Platform")

        val keyFile = File("config/spp-platform.key")
        val certFile = File("config/spp-platform.crt")
        if ("true".equals(System.getenv("SPP_DISABLE_TLS"), true)) {
            if ("true".equals(System.getenv("SPP_DISABLE_JWT"), true)) {
                log.warn("Skipped generating security certificates")
            } else if (keyFile.exists() && certFile.exists()) {
                log.info("Using existing security certificates")
            }
        } else if (!keyFile.exists() || !certFile.exists()) {
            generateSecurityCertificates(keyFile, certFile)
        } else {
            log.info("Using existing security certificates")
        }

        val jwt: JWTAuth?
        if ("true".equals(System.getenv("SPP_DISABLE_JWT"), true)) {
            jwt = null
        } else {
            if (!keyFile.exists() || !certFile.exists()) {
                generateSecurityCertificates(keyFile, certFile)
            }
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
            if ("true".equals(System.getenv("SPP_DISABLE_JWT"), true)) {
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
                SourceStorage.setup(redisStorage, config)
                sessionStore = RedisSessionStore.create(vertx, redisStorage.redisClient)
            }
            else -> {
                log.error("Unknown storage selector: $storageSelector")
                throw IllegalArgumentException("Unknown storage selector: $storageSelector")
            }
        }

        //Setup dashboard
        val sessionHandler = SessionHandler.create(sessionStore)
        router.route().handler(sessionHandler)
        setupDashboard(router)

        if (System.getenv("SPP_DISABLE_JWT") != "true") {
            router.route("/*").handler(JWTAuthHandler.create(jwt))
        } else {
            log.warn("JWT authentication disabled")
        }

        //S++ Graphql
        val sppGraphQLHandler = GraphQLHandler.create(SourceService.setupGraphQL(vertx))
        router.route("/graphql").handler(BodyHandler.create()).handler {
            if (it.request().getHeader("spp-platform-request") == "true") {
                sppGraphQLHandler.handle(it)
            } else {
                it.reroute("/graphql/skywalking")
            }
        }
        router.route("/graphql/spp").handler(BodyHandler.create()).handler {
            sppGraphQLHandler.handle(it)
        }

        //Health checks
        val healthChecks = HealthChecks.create(vertx)
        addServiceCheck(healthChecks, Utilize.LIVE_SERVICE)
        router["/health"].handler(HealthCheckHandler.createWithHealthChecks(healthChecks))
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)

        log.info("Starting service discovery")
        discovery = ServiceDiscovery.create(vertx)

        vertx.eventBus().consumer<JsonObject>(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS) {
            val record = Record(it.body())
            if (record.status == Status.UP) {
                launch(vertx.dispatcher()) {
                    if (record.name.startsWith("spp.")) {
                        //todo: this feels hacky
                        Reflect.on(discovery).get<DefaultServiceDiscoveryBackend>("backend").store(record) {
                            if (it.failed()) {
                                it.cause().printStackTrace()
                            }
                        }
                    }
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

        val netServerOptions = NetServerOptions()
            .removeEnabledSecureTransportProtocol("SSLv2Hello")
            .removeEnabledSecureTransportProtocol("TLSv1")
            .removeEnabledSecureTransportProtocol("TLSv1.1")
            .setSsl(System.getenv("SPP_DISABLE_TLS") != "true")
            .apply {
                if (System.getenv("SPP_DISABLE_TLS") != "true") {
                    pemKeyCertOptions = PemKeyCertOptions()
                        .setKeyValue(Buffer.buffer(keyFile.readText()))
                        .setCertValue(Buffer.buffer(certFile.readText()))
                }
            }

        //Open bridges
        vertx.deployVerticle(
            ProbeBridge(router, jwt, netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("probe"))
        ).await()
        vertx.deployVerticle(
            MarkerBridge(jwt, netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("marker"))
        ).await()
        vertx.deployVerticle(
            ProcessorBridge(healthChecks, jwt, netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("processor"))
        ).await()

        //Start services
        vertx.deployVerticle(
            ServiceProvider(jwt), DeploymentOptions().setConfig(config.put("SPP_INSTANCE_ID", SPP_INSTANCE_ID))
        ).await()

        //Start SkyWalking proxy
        vertx.deployVerticle(
            SkyWalkingInterceptor(router), DeploymentOptions().setConfig(config)
        ).await()

        log.debug("Starting API server")
        if (System.getenv("SPP_DISABLE_TLS") == "true") {
            log.warn("TLS protocol disabled")
        }

        val httpPort = vertx.sharedData().getLocalMap<String, Int>("spp.core")
            .getOrDefault("http.port", config.getJsonObject("spp-platform").getString("port").toInt())
        val httpOptions = HttpServerOptions().setSsl(System.getenv("SPP_DISABLE_TLS") != "true")
        if (System.getenv("SPP_DISABLE_TLS") != "true") {
            val jksOptions = CertsToJksOptionsConverter(certFile.absolutePath, keyFile.absolutePath).createJksOptions()
            httpOptions.setKeyStoreOptions(jksOptions)
        }
        val server = vertx.createHttpServer(httpOptions)
            .requestHandler(router)
            .listen(httpPort, config.getJsonObject("spp-platform").getString("host")).await()
        vertx.sharedData().getLocalMap<String, Int>("spp.core")["http.port"] = server.actualPort()
        log.info("API server started. Port: {}", server.actualPort())
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
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
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
                        .put("processors", JsonArray(Json.encode(ProcessorBridge.getActiveProcessors(vertx))))
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
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
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
            .put("connected-processors", ProcessorBridge.getConnectedProcessorCount(vertx))
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
