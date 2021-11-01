package spp.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.sourceplusplus.protocol.ProtocolMarshaller
import com.sourceplusplus.protocol.ProtocolMarshaller.ProtocolMessageCodec
import com.sourceplusplus.protocol.SourceMarkerServices.Utilize
import io.vertx.core.DeploymentOptions
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
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
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisAPI
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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.graalvm.nativeimage.ImageInfo
import org.slf4j.LoggerFactory
import spp.platform.core.SourceService
import spp.platform.core.SourceServiceDiscovery
import spp.platform.core.SourceStorage
import spp.platform.marker.MarkerTracker
import spp.platform.marker.MarkerVerticle
import spp.platform.probe.ProbeTracker
import spp.platform.probe.ProbeVerticle
import spp.platform.probe.config.SourceProbeConfig
import spp.platform.probe.util.SelfSignedCertGenerator
import spp.platform.processor.ProcessorTracker
import spp.platform.processor.ProcessorVerticle
import spp.platform.util.CertsToJksOptionsConverter
import spp.platform.util.KSerializers
import spp.platform.util.Msg.msg
import spp.platform.util.RequestContext
import spp.protocol.platform.PlatformAddress
import spp.protocol.probe.ProbeAddress
import spp.protocol.processor.ProcessorAddress
import spp.provider.ServiceProvider
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
            if (!ImageInfo.inImageBuildtimeCode() && File("config/logback.xml").exists()) {
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
        lateinit var redis: RedisAPI

        @JvmStatic
        fun main(args: Array<String>) {
            val yamlMapper = YAMLMapper()
            val yaml = yamlMapper.readValue(File("config/spp-platform.yml"), Object::class.java)
            val sppConfig = JsonObject(ObjectMapper().writeValueAsString(yaml))

            if (USE_DEFAULT_LOGGING_CONFIGURATION) {
                val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
                loggerContext.loggerList.filter { it.name.startsWith("spp") }.forEach {
                    it.level = Level.toLevel(
                        sppConfig.getJsonObject("spp-platform").getJsonObject("logging").getString("level")
                    )
                }
            }
            log.info("Booting Source++ Platform [v${BUILD.getString("build_version")}]")

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
        if (!keyFile.exists() || !certFile.exists()) {
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
        } else {
            log.info("Using existing security certificates")
        }

        val sppTlsKey = keyFile.readText()
        val sppTlsCert = certFile.readText()

        val keyPair = PEMParser(StringReader(File("config/spp-platform.key").readText())).use {
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

        val jwt: JWTAuth?
        if ("true".equals(System.getenv("SPP_DISABLE_JWT"), true)) {
            jwt = null
        } else {
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
                log.debug("Invalid token request")
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
            redis.sismember("developers:access_tokens", token).onComplete {
                if (it.succeeded() && it.result().toBoolean()) {
                    doProbeGeneration(route)
                } else if (it.succeeded()) {
                    log.warn("Rejected invalid access token: {}", token)
                    route.response().setStatusCode(401).end()
                } else {
                    log.error("Failed to query access tokens", it.cause())
                    route.response().setStatusCode(500).end(it.cause().message)
                }
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
        router.route("/graphql/skywalking").handler(BodyHandler.create()).handler { req ->
            GlobalScope.launch(vertx.dispatcher()) {
                log.trace { msg("Forwarding SkyWalking request: {}", req.bodyAsString) }
                val forward = httpClient.request(
                    req.request().method(), skywalkingPort, skywalkingHost, "/graphql"
                ).await()

                forward.response().onComplete { resp ->
                    resp.result().body().onComplete {
                        val respBody = it.result()
                        log.trace { msg("Forwarding SkyWalking response: {}", respBody.toString()) }
                        req.response().setStatusCode(resp.result().statusCode()).end(respBody)
                    }
                }

                req.request().headers().names().forEach {
                    forward.putHeader(it, req.request().headers().get(it))
                }
                forward.end(req.body).await()
            }
        }

        //Health checks
        val checks = HealthChecks.create(vertx)
        addServiceCheck(checks, ProcessorAddress.LIVE_VIEW_PROCESSOR.address)
        addServiceCheck(checks, ProcessorAddress.LIVE_INSTRUMENT_PROCESSOR.address)
        addServiceCheck(checks, ProcessorAddress.LOGGING_PROCESSOR.address)
        addServiceCheck(checks, Utilize.LIVE_VIEW)
        addServiceCheck(checks, Utilize.LIVE_INSTRUMENT)
        addServiceCheck(checks, Utilize.LOG_COUNT_INDICATOR)
        router["/health"].handler(HealthCheckHandler.createWithHealthChecks(checks))
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)

        log.info("Connecting to storage")
        val sdHost = System.getenv("SPP_REDIS_HOST") ?: config.getJsonObject("redis").getString("host")
        val sdPort = System.getenv("SPP_REDIS_PORT") ?: config.getJsonObject("redis").getInteger("port")
        val redisClient = Redis.createClient(vertx, "redis://$sdHost:$sdPort").connect().await()
        redis = RedisAPI.api(redisClient)

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

        //Start platform
        vertx.deployVerticle(
            ProbeVerticle(sppTlsKey, sppTlsCert),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("probe"))
        ).await()
        vertx.deployVerticle(
            MarkerVerticle(jwt, sppTlsKey, sppTlsCert),
            DeploymentOptions().setConfig(config.getJsonObject("spp-platform").getJsonObject("marker"))
        ).await()
        vertx.deployVerticle(
            ProcessorVerticle(sppTlsKey, sppTlsCert),
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
        val jksOptions = CertsToJksOptionsConverter(certFile.absolutePath, keyFile.absolutePath).createJksOptions()
        httpOptions.setKeyStoreOptions(jksOptions)
        val server = vertx.createHttpServer(httpOptions)
            .requestHandler(router)
            .listen(httpPort, config.getJsonObject("spp-platform").getString("host")).await()
        vertx.sharedData().getLocalMap<String, Int>("spp.core")["http.port"] = server.actualPort()
        log.info("API server started. Port: {}", server.actualPort())

        SourceStorage.setup(redis)
        if (!System.getenv("SPP_SYSTEM_ACCESS_TOKEN").isNullOrBlank()) {
            SourceStorage.setAccessToken("system", System.getenv("SPP_SYSTEM_ACCESS_TOKEN"))
        } else {
            val systemAccessToken = config.getJsonObject("spp-platform").getString("access_token")
            if (systemAccessToken != null) {
                SourceStorage.setAccessToken("system", systemAccessToken)
            }
        }
        log.debug("Source++ Platform initialized")
    }

    private fun doProbeGeneration(route: RoutingContext) {
        log.debug("Generating signed probe")
        val platformHost = System.getenv("SPP_CLUSTER_URL") ?: "localhost"
        val platformName = System.getenv("SPP_CLUSTER_NAME") ?: "unknown"
        val config = SourceProbeConfig(platformHost, platformName)
        vertx.eventBus().request<String>(PlatformAddress.GENERATE_PROBE.address, config) {
            if (it.succeeded()) {
                val signedProbe = File(it.result().body())
                GlobalScope.launch(vertx.dispatcher()) {
                    route.response()
                        .putHeader("content-disposition", "attachment; filename=${signedProbe.name}")
                        .sendFile(it.result().body())
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
        val registeredName = "services/${serviceName.replace("sm.", "spp.").replace(".", "/")}"
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
                                Utilize.LIVE_INSTRUMENT.replace("sm.", "spp."),
                                vertx.sharedData()
                                    .getLocalCounter(Utilize.LIVE_INSTRUMENT)
                                    .await().get().await()
                            )
                            .put(
                                Utilize.LIVE_VIEW.replace("sm.", "spp."),
                                vertx.sharedData()
                                    .getLocalCounter(Utilize.LIVE_VIEW)
                                    .await().get().await()
                            )
                            .put(
                                Utilize.LOG_COUNT_INDICATOR.replace("sm.", "spp."),
                                vertx.sharedData()
                                    .getLocalCounter(Utilize.LOG_COUNT_INDICATOR)
                                    .await().get().await()
                            )
                    )
                    .put(
                        "processor",
                        JsonObject()
                            .put(
                                ProcessorAddress.LIVE_INSTRUMENT_PROCESSOR.address,
                                vertx.sharedData()
                                    .getLocalCounter(ProcessorAddress.LIVE_INSTRUMENT_PROCESSOR.address)
                                    .await().get().await()
                            )
                            .put(
                                ProcessorAddress.LIVE_VIEW_PROCESSOR.address,
                                vertx.sharedData()
                                    .getLocalCounter(ProcessorAddress.LIVE_VIEW_PROCESSOR.address)
                                    .await().get().await()
                            )
                            .put(
                                ProcessorAddress.LOGGING_PROCESSOR.address,
                                vertx.sharedData()
                                    .getLocalCounter(ProcessorAddress.LOGGING_PROCESSOR.address)
                                    .await().get().await()
                            )
                    )
                    .put(
                        "probe",
                        JsonObject()
                            .put(
                                ProbeAddress.LIVE_BREAKPOINT_REMOTE.address,
                                vertx.sharedData()
                                    .getLocalCounter(ProbeAddress.LIVE_BREAKPOINT_REMOTE.address)
                                    .await().get().await()
                            )
                            .put(
                                ProbeAddress.LIVE_LOG_REMOTE.address,
                                vertx.sharedData()
                                    .getLocalCounter(ProbeAddress.LIVE_LOG_REMOTE.address)
                                    .await().get().await()
                            )
                            .put(
                                ProbeAddress.LIVE_METER_REMOTE.address,
                                vertx.sharedData()
                                    .getLocalCounter(ProbeAddress.LIVE_METER_REMOTE.address)
                                    .await().get().await()
                            )
                    )
            )
    }
}
