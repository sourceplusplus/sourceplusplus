package spp.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.vertx.core.DeploymentOptions
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.http.ClientAuth
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
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
import io.vertx.ext.web.handler.graphql.GraphQLHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.Status
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.apache.commons.lang3.math.NumberUtils
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.lookup.StringLookupFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.slf4j.LoggerFactory
import spp.platform.core.SourceService
import spp.platform.core.SourceServiceDiscovery
import spp.platform.core.SourceStorage
import spp.platform.core.storage.MemoryStorage
import spp.platform.core.storage.RedisStorage
import spp.platform.marker.MarkerTracker
import spp.platform.marker.MarkerVerticle
import spp.platform.probe.ProbeTracker
import spp.platform.probe.ProbeVerticle
import spp.platform.probe.config.SourceProbeConfig
import spp.platform.probe.util.SelfSignedCertGenerator
import spp.platform.processor.ProcessorTracker
import spp.platform.processor.ProcessorVerticle
import spp.platform.util.CertsToJksOptionsConverter
import spp.platform.util.Msg.msg
import spp.platform.util.RequestContext
import spp.protocol.ProtocolMarshaller
import spp.protocol.ProtocolMarshaller.ProtocolMessageCodec
import spp.protocol.SourceMarkerServices.Utilize
import spp.protocol.platform.PlatformAddress
import spp.protocol.probe.ProbeAddress.*
import spp.protocol.processor.ProcessorAddress.*
import spp.protocol.util.KSerializers
import spp.service.ServiceProvider
import java.io.File
import java.io.FileWriter
import java.io.StringReader
import java.io.StringWriter
import java.security.Security
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
            val yamlMapper = YAMLMapper()
            val yaml = yamlMapper.readValue(File("config/spp-platform.yml"), Object::class.java)
            val sppConfig = JsonObject(
                StringSubstitutor(StringLookupFactory.INSTANCE.environmentVariableStringLookup()).replace(
                    ObjectMapper().writeValueAsString(yaml)
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
                    val vertxOptions = VertxOptions()
                    vertxOptions.blockedThreadCheckInterval = Int.MAX_VALUE.toLong()
                    val vertx = Vertx.vertx(vertxOptions)
                    vertx.deployVerticle(SourcePlatform(), DeploymentOptions().setConfig(sppConfig)).await()
                } catch (ex: Throwable) {
                    ex.printStackTrace()
                    exitProcess(-1)
                }
            }
        }
    }

    @Suppress("LongMethod")
    override suspend fun start() {
        log.info("Initializing Source++ Platform")

        val module = SimpleModule()
        module.addSerializer(Instant::class.java, KSerializers.KotlinInstantSerializer())
        module.addDeserializer(Instant::class.java, KSerializers.KotlinInstantDeserializer())
        DatabindCodec.mapper().registerModule(module)
        ProtocolMarshaller.setupCodecs(vertx)
        vertx.eventBus().registerDefaultCodec(SourceProbeConfig::class.java, ProtocolMessageCodec())
        vertx.eventBus().registerDefaultCodec(ArrayList::class.java, ProtocolMessageCodec())

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
            writer.close()
            val privateKey = StringWriter()
            writer = JcaPEMWriter(privateKey)
            writer.writeObject(keyPair.private)
            writer.close()

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
                log.warn("Invalid token request")
                ctx.response().setStatusCode(401).end()
                return@handler
            }

            val token = accessTokenParam[0]
            log.debug("Verifying access token: $token")
            GlobalScope.launch {
                val dev = SourceStorage.getDeveloperByAccessToken(token)
                if (dev != null) {
                    val jwtToken = jwt!!.generateToken(
                        JsonObject()
                            .put("developer_id", dev.id)
                            .put("created_at", java.time.Instant.now().toEpochMilli())
                            .put("expires_at", java.time.Instant.now().plus(365, ChronoUnit.DAYS).toEpochMilli()),
                        JWTOptions().setAlgorithm("RS256")
                    )
                    ctx.end(jwtToken)
                } else {
                    ctx.response().setStatusCode(401).end()
                }
            }
        }

        if (System.getenv("SPP_DISABLE_JWT") != "true") {
            val authHandler = JWTAuthHandler.create(jwt)
            router.route("/clients").handler(authHandler)
            router.route("/stats").handler(authHandler)
            router.route("/health").handler(authHandler)
            router.route("/api/*").handler(authHandler)
            router.route("/graphql/*").handler(authHandler)
        } else {
            log.warn("JWT authentication disabled")
        }

        router["/download/spp-probe"].handler { route ->
            if (System.getenv("SPP_DISABLE_JWT") == "true") {
                doProbeGeneration(route)
                return@handler
            }

            val token = route.request().getParam("access_token")
            log.info("Probe download request. Verifying access token: {}", token)
            GlobalScope.launch {
                SourceStorage.getDeveloperByAccessToken(token)?.let {
                    doProbeGeneration(route)
                } ?: route.response().setStatusCode(401).end()
            }
        }

        //S++ Graphql
        router.route("/graphql").handler(BodyHandler.create())
            .handler(GraphQLHandler.create(SourceService.setupGraphQL(vertx)))

        //SkyWalking Graphql
        val skywalkingHost = System.getenv("SPP_SKYWALKING_HOST")
            ?: config.getJsonObject("skywalking-oap").getString("host")
        val skywalkingPort = NumberUtils.toInt(
            System.getenv("SPP_SKYWALKING_PORT"),
            config.getJsonObject("skywalking-oap").getInteger("port")
        )
        val httpClient = vertx.createHttpClient()
        vertx.eventBus().consumer<JsonObject>("skywalking-forwarder") { req ->
            val request = req.body()
            val body = request.getString("body")!!
            val headers: JsonObject? = request.getJsonObject("headers")
            val method = HttpMethod.valueOf(request.getString("method"))!!
            log.trace { msg("Forwarding SkyWalking request: {}", body) }

            GlobalScope.launch(vertx.dispatcher()) {
                val forward = httpClient.request(
                    method, skywalkingPort, skywalkingHost, "/graphql"
                ).await()

                forward.response().onComplete { resp ->
                    resp.result().body().onComplete {
                        val respBody = it.result()
                        log.trace { msg("Forwarding SkyWalking response: {}", respBody.toString()) }
                        val respOb = JsonObject()
                        respOb.put("status", resp.result().statusCode())
                        respOb.put("body", respBody.toString())
                        req.reply(respOb)
                    }
                }

                headers?.fieldNames()?.forEach {
                    forward.putHeader(it, headers.getValue(it).toString())
                }
                forward.end(body).await()
            }
        }

        router.route("/graphql/skywalking").handler(BodyHandler.create()).handler { req ->
            val forward = JsonObject()
            forward.put("body", req.bodyAsString)
            val headers = JsonObject()
            req.request().headers().names().forEach {
                headers.put(it, req.request().headers().get(it))
            }
            forward.put("headers", headers)
            forward.put("method", req.request().method().name())
            vertx.eventBus().request<JsonObject>("skywalking-forwarder", forward) {
                if (it.succeeded()) {
                    val resp = it.result().body()
                    req.response().setStatusCode(resp.getInteger("status")).end(resp.getString("body"))
                } else {
                    log.error("Failed to forward SkyWalking request", it.cause())
                }
            }
        }

        //Health checks
        val checks = HealthChecks.create(vertx)
        addServiceCheck(checks, LIVE_VIEW_PROCESSOR.address)
        addServiceCheck(checks, LIVE_INSTRUMENT_PROCESSOR.address)
        addServiceCheck(checks, LOGGING_PROCESSOR.address)
        addServiceCheck(checks, Utilize.LIVE_SERVICE)
        addServiceCheck(checks, Utilize.LIVE_INSTRUMENT)
        addServiceCheck(checks, Utilize.LIVE_VIEW)
        addServiceCheck(checks, Utilize.LOG_COUNT_INDICATOR)
        router["/health"].handler(HealthCheckHandler.createWithHealthChecks(checks))
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)

        //Setup storage
        when (val storageSelector = config.getJsonObject("storage").getString("selector")) {
            "memory" -> {
                log.info("Using in-memory storage")
                SourceStorage.setup(MemoryStorage(vertx), config)
            }
            "redis" -> {
                log.info("Using Redis storage")
                val redisStorage = RedisStorage()
                redisStorage.init(vertx, config)
                SourceStorage.setup(redisStorage, config)
            }
            else -> {
                log.error("Unknown storage selector: $storageSelector")
                throw IllegalArgumentException("Unknown storage selector: $storageSelector")
            }
        }

        log.info("Starting service discovery")
        discovery = ServiceDiscovery.create(
            vertx,
            ServiceDiscoveryOptions().setBackendConfiguration(
                JsonObject().put("backend-name", "spp.platform.core.SourceServiceDiscovery")
            )
        )

        vertx.eventBus().consumer<JsonObject>(ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS) {
            val record = Record(it.body())
            if (record.status == Status.UP) {
                GlobalScope.launch(vertx.dispatcher()) {
                    if (record.name.startsWith("sw.")) {
                        //todo: this feels hacky
                        SourceServiceDiscovery.INSTANCE.store(record) {
                            if (it.failed()) {
                                it.cause().printStackTrace()
                            }
                        }
                    }
                    vertx.sharedData().getLocalCounter(record.name).await().andIncrement.await()
                    log.trace { "Service UP: ${record.name}" }
                }
            } else if (record.status == Status.DOWN) {
                GlobalScope.launch(vertx.dispatcher()) {
                    vertx.sharedData().getLocalCounter(record.name).await().decrementAndGet().await()
                    log.trace { "Service DOWN: ${record.name}" }
                }
            }
        }

        //todo: standardize
        vertx.eventBus().consumer<JsonObject>("get-records") {
            launch {
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
            .setSsl(System.getenv("SPP_DISABLE_TLS") != "true").setClientAuth(ClientAuth.REQUEST)
            .apply {
                if (System.getenv("SPP_DISABLE_TLS") != "true") {
                    pemKeyCertOptions = PemKeyCertOptions()
                        .setKeyValue(Buffer.buffer(keyFile.readText()))
                        .setCertValue(Buffer.buffer(certFile.readText()))
                }
            }

        //Start platform
        vertx.deployVerticle(
            ProbeVerticle(netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("probe"))
        ).await()
        vertx.deployVerticle(
            MarkerVerticle(jwt, netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("marker"))
        ).await()
        vertx.deployVerticle(
            ProcessorVerticle(netServerOptions),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("processor"))
        ).await()

        //Start services
        vertx.deployVerticle(
            ServiceProvider(jwt), DeploymentOptions().setConfig(config.put("SPP_INSTANCE_ID", SPP_INSTANCE_ID))
        ).await()

        log.debug("Starting API server")
        if (System.getenv("SPP_DISABLE_TLS") == "true") {
            log.warn("TLS protocol disabled")
        }

        val httpPort = vertx.sharedData().getLocalMap<String, Int>("spp.core")
            .getOrDefault("http.port", config.getJsonObject("spp-platform").getInteger("port"))
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

    private fun doProbeGeneration(route: RoutingContext) {
        log.debug("Generating signed probe")
        val platformHost = System.getenv("SPP_CLUSTER_URL") ?: "localhost"
        val platformName = System.getenv("SPP_CLUSTER_NAME") ?: "unknown"
        val probeVersion = route.queryParam("version")
        val config = if (probeVersion.isNotEmpty()) {
            SourceProbeConfig(platformHost, platformName, probeVersion = probeVersion[0])
        } else {
            SourceProbeConfig(platformHost, platformName, probeVersion = "latest")
        }

        vertx.eventBus().request<JsonObject>(PlatformAddress.GENERATE_PROBE.address, config) {
            if (it.succeeded()) {
                GlobalScope.launch(vertx.dispatcher()) {
                    val genProbe = it.result().body()
                    route.response().putHeader(
                        "content-disposition",
                        "attachment; filename=spp-probe-${genProbe.getString("probe_version")}.jar"
                    ).sendFile(genProbe.getString("file_location"))
                    log.info("Signed probe downloaded")
                }
            } else {
                log.error("Failed to generate signed probe", it.cause())
                val replyEx = it.cause() as ReplyException
                route.response().setStatusCode(replyEx.failureCode())
                    .end(it.cause().message)
            }
        }
    }

    private fun addServiceCheck(checks: HealthChecks, serviceName: String) {
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
        log.info("Get platform clients request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            ctx.response().putHeader("Content-Type", "application/json")
                .end(
                    JsonObject()
                        .put("processors", JsonArray(Json.encode(ProcessorTracker.getActiveProcessors(vertx))))
                        .put("markers", JsonArray(Json.encode(MarkerTracker.getActiveMarkers(vertx))))
                        .put("probes", JsonArray(Json.encode(ProbeTracker.getActiveProbes(vertx))))
                        .toString()
                )
        }
    }

    private fun getStats(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        if (selfId == null) {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info("Get platform stats request. Developer: {}", selfId)

        GlobalScope.launch(vertx.dispatcher()) {
            val promise = Promise.promise<JsonObject>()
            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveView.getLiveViewSubscriptionStats(promise)
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
            .put("connected-processors", ProcessorTracker.getConnectedProcessorCount(vertx))
            .put("connected-markers", MarkerTracker.getConnectedMarkerCount(vertx))
            .put("connected-probes", ProbeTracker.getConnectedProbeCount(vertx))
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
                            .put(
                                Utilize.LOG_COUNT_INDICATOR,
                                vertx.sharedData().getLocalCounter(Utilize.LOG_COUNT_INDICATOR).await().get().await()
                            )
                    )
                    .put(
                        "processor",
                        JsonObject()
                            .put(
                                LIVE_INSTRUMENT_PROCESSOR.address,
                                vertx.sharedData().getLocalCounter(LIVE_INSTRUMENT_PROCESSOR.address)
                                    .await().get().await()
                            )
                            .put(
                                LIVE_VIEW_PROCESSOR.address,
                                vertx.sharedData().getLocalCounter(LIVE_VIEW_PROCESSOR.address)
                                    .await().get().await()
                            )
                            .put(
                                LOGGING_PROCESSOR.address,
                                vertx.sharedData().getLocalCounter(LOGGING_PROCESSOR.address)
                                    .await().get().await()
                            )
                    )
                    .put(
                        "probe",
                        JsonObject()
                            .put(
                                LIVE_BREAKPOINT_REMOTE.address,
                                vertx.sharedData().getLocalCounter(LIVE_BREAKPOINT_REMOTE.address)
                                    .await().get().await()
                            )
                            .put(
                                LIVE_LOG_REMOTE.address,
                                vertx.sharedData().getLocalCounter(LIVE_LOG_REMOTE.address)
                                    .await().get().await()
                            )
                            .put(
                                LIVE_METER_REMOTE.address,
                                vertx.sharedData().getLocalCounter(LIVE_METER_REMOTE.address)
                                    .await().get().await()
                            )
                    )
            )
    }
}
