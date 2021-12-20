package spp.service

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.core.SourceStorage
import spp.platform.util.RequestContext
import spp.protocol.SourceMarkerServices.Utilize
import spp.protocol.auth.error.AccessDenied
import spp.protocol.auth.error.InstrumentAccessDenied
import spp.protocol.service.LiveService
import spp.protocol.service.live.LiveInstrumentService
import spp.protocol.service.live.LiveViewService
import spp.protocol.service.logging.LogCountIndicatorService
import spp.service.live.LiveProviders
import spp.service.logging.LoggingProviders
import kotlin.system.exitProcess

class ServiceProvider(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(ServiceProvider::class.java)
        lateinit var loggingProviders: LoggingProviders
        lateinit var liveProviders: LiveProviders
    }

    private var discovery: ServiceDiscovery? = null
    private var liveService: Record? = null
    private var liveInstrument: Record? = null
    private var logCountIndicator: Record? = null
    private var liveView: Record? = null

    override suspend fun start() {
        try {
            val sdHost = System.getenv("SPP_REDIS_HOST") ?: config.getJsonObject("redis").getString("host")
            val sdPort = System.getenv("SPP_REDIS_PORT") ?: config.getJsonObject("redis").getInteger("port")
            discovery = ServiceDiscovery.create(
                vertx,
                ServiceDiscoveryOptions().setBackendConfiguration(
                    JsonObject()
                        .put("connectionString", "redis://$sdHost:$sdPort")
                        .put("key", "records")
                )
            )
            loggingProviders = LoggingProviders(vertx, discovery!!)
            liveProviders = LiveProviders(vertx, discovery!!)

            liveService = publishService(
                Utilize.LIVE_SERVICE,
                LiveService::class.java,
                liveProviders.liveService
            )
            liveInstrument = publishService(
                Utilize.LIVE_INSTRUMENT,
                LiveInstrumentService::class.java,
                liveProviders.liveInstrument
            )
            liveView = publishService(
                Utilize.LIVE_VIEW,
                LiveViewService::class.java,
                liveProviders.liveView
            )
            logCountIndicator = publishService(
                Utilize.LOG_COUNT_INDICATOR,
                LogCountIndicatorService::class.java,
                loggingProviders.logCountIndicator
            )
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            log.error("Failed to start SkyWalking provider", throwable)
            exitProcess(-1)
        }
    }

    private suspend fun <T> publishService(address: String, clazz: Class<T>, service: T): Record {
        ServiceBinder(vertx).setIncludeDebugInfo(true).setAddress(address).apply {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                addInterceptor { msg ->
                    if (log.isTraceEnabled) log.trace("Validating $address access")

                    val promise = Promise.promise<Message<JsonObject>>()
                    jwtAuth!!.authenticate(JsonObject().put("token", msg.headers().get("auth-token"))).onComplete {
                        GlobalScope.launch {
                            if (it.succeeded()) {
                                val selfId = it.result().principal().getString("developer_id")
                                msg.headers().add("self_id", selfId)
                                RequestContext.put("self_id", selfId)

                                if (msg.headers().get("action").startsWith("addLiveInstrument")) {
                                    validateInstrumentAccess(selfId, msg, promise)
                                } else {
                                    promise.complete(msg)
                                }
                            } else {
                                log.error("Unauthorized $address access", it.cause())
                                val replyEx = ReplyException(
                                    ReplyFailure.RECIPIENT_FAILURE, 401,
                                    Json.encode(AccessDenied(it.cause().message!!).toEventBusException())
                                )
                                promise.fail(replyEx)
                            }
                        }
                    }
                    return@addInterceptor promise.future()
                }
            } else {
                addInterceptor { msg ->
                    if (log.isTraceEnabled) log.trace("Skipping $address access validation")

                    val selfId = "system"
                    msg.headers().add("self_id", selfId)
                    RequestContext.put("self_id", selfId)
                    return@addInterceptor Future.succeededFuture(msg)
                }
            }
        }.register(clazz, service)
        val record = EventBusService.createRecord(
            address, address, clazz,
            JsonObject().put("INSTANCE_ID", config.getString("SPP_INSTANCE_ID"))
        )
        discovery!!.publish(record).await()
        log.info("$address service enabled")
        return record
    }

    private suspend fun validateInstrumentAccess(
        selfId: String, msg: Message<JsonObject>, promise: Promise<Message<JsonObject>>
    ) {
        if (msg.headers().get("action") == "addLiveInstruments") {
            val instruments = msg.body().getJsonObject("batch").getJsonArray("instruments")
            for (i in 0 until instruments.size()) {
                val sourceLocation = instruments.getJsonObject(i)
                    .getJsonObject("location").getString("source")
                if (!SourceStorage.hasInstrumentAccess(selfId, sourceLocation)) {
                    log.warn("Rejected developer {} unauthorized instrument access to: {}", selfId, sourceLocation)
                    val replyEx = ReplyException(
                        ReplyFailure.RECIPIENT_FAILURE, 403,
                        Json.encode(InstrumentAccessDenied(sourceLocation).toEventBusException())
                    )
                    promise.fail(replyEx)
                    return
                }
            }
            promise.complete(msg)
        } else {
            val sourceLocation = msg.body().getJsonObject("instrument")
                .getJsonObject("location").getString("source")
            if (!SourceStorage.hasInstrumentAccess(selfId, sourceLocation)) {
                log.warn("Rejected developer {} unauthorized instrument access to: {}", selfId, sourceLocation)
                val replyEx = ReplyException(
                    ReplyFailure.RECIPIENT_FAILURE, 403,
                    Json.encode(InstrumentAccessDenied(sourceLocation).toEventBusException())
                )
                promise.fail(replyEx)
            } else {
                promise.complete(msg)
            }
        }
    }

    override suspend fun stop() {
        discovery!!.unpublish(logCountIndicator!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Log count indicator service unpublished")
            } else {
                log.error("Failed to unpublish log count indicator service", it.cause())
            }
        }.await()
        discovery!!.unpublish(liveInstrument!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live instrument service unpublished")
            } else {
                log.error("Failed to unpublish live instrument service", it.cause())
            }
        }.await()
        discovery!!.unpublish(liveView!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live view service unpublished")
            } else {
                log.error("Failed to unpublish live view service", it.cause())
            }
        }.await()
        discovery!!.close()
    }
}
