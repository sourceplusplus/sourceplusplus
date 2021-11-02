package spp.processor

import io.vertx.core.AsyncResult
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameParser
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.slf4j.LoggerFactory
import spp.protocol.platform.PlatformAddress
import spp.protocol.processor.ProcessorAddress
import spp.protocol.processor.status.ProcessorConnection
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object LogSummaryProcessor {

    private val log = LoggerFactory.getLogger(LogSummaryProcessor::class.java)
    val INSTANCE_ID = UUID.randomUUID().toString()
    var module: ModuleManager? = null
    var options: VertxOptions? = null
    var vertx: Vertx
    var discovery: ServiceDiscovery
    private val connected = AtomicBoolean()
    private var processorVerticleId: String? = null
    var tcpSocket: NetSocket? = null

    init {
        options = VertxOptions()

        runBlocking {
            log.info("Deploying node")
            vertx = Vertx.vertx(options)

            republishEvents(vertx, ServiceDiscoveryOptions.DEFAULT_ANNOUNCE_ADDRESS)
            republishEvents(vertx, ServiceDiscoveryOptions.DEFAULT_USAGE_ADDRESS)

            try {
                discovery = ServiceDiscovery.create(vertx)
                connectToPlatform()
            } catch (ex: Throwable) {
                ex.printStackTrace()
                log.error("Platform connection credentials missing", ex)
                exitProcess(-1)
            }
        }
    }

    @Synchronized
    private fun connectToPlatform() {
        if (connected.get()) return
        val platformHost = System.getenv("SPP_PLATFORM_HOST")!!
        val platformPort = System.getenv("SPP_PLATFORM_PORT").toInt()
        val trustAll = System.getenv("SPP_PLATFORM_SSL_TRUST_ALL") == "true"
        val platformCertificate = System.getenv("SPP_PLATFORM_CERTIFICATE")
        val platformCertificateFile = System.getenv("SPP_PLATFORM_CERTIFICATE_FILE")
        val myCaAsABuffer = when {
            platformCertificateFile != null -> Buffer.buffer(File(platformCertificateFile).readText())
            platformCertificate != null -> Buffer.buffer(
                "-----BEGIN CERTIFICATE-----$platformCertificate-----END CERTIFICATE-----"
            )
            else -> null
        }
        val options = when {
            myCaAsABuffer != null -> NetClientOptions()
                .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                .setSsl(System.getenv("SPP_DISABLE_TLS") != "true")
                .setPemTrustOptions(PemTrustOptions().addCertValue(myCaAsABuffer))
            else -> NetClientOptions()
                .setReconnectAttempts(Int.MAX_VALUE).setReconnectInterval(5000)
                .setSsl(System.getenv("SPP_DISABLE_TLS") != "true")
                .setTrustAll(trustAll)
        }
        val client: NetClient = vertx.createNetClient(options)
        client.connect(platformPort, platformHost) { socket: AsyncResult<NetSocket> ->
            if (socket.failed()) {
                log.error("Failed to connect to Source++ Platform")
                socket.cause().printStackTrace()
                GlobalScope.launch(vertx.dispatcher()) {
                    delay(5000) //todo: impl circuit breaker
                    connectToPlatform()
                }
                return@connect
            } else {
                log.info("Connected to Source++ Platform")
                tcpSocket = socket.result()
                connected.set(true) //todo: need to do compareAndSet to prevent multiple connection
            }
            socket.result().exceptionHandler {
                connected.set(false)
                vertx.undeploy(processorVerticleId)
                connectToPlatform()
            }
            socket.result().closeHandler {
                connected.set(false)
                vertx.undeploy(processorVerticleId)
                connectToPlatform()
            }

            //handle platform messages
            val parser = FrameParser { parse: AsyncResult<JsonObject> ->
                val frame = parse.result()
                if ("message" == frame.getString("type")) {
                    if (frame.getString("replyAddress") != null) {
                        val deliveryOptions = DeliveryOptions()
                        frame.getJsonObject("headers").fieldNames().forEach {
                            deliveryOptions.addHeader(it, frame.getJsonObject("headers").getString(it))
                        }
                        vertx.eventBus().request<Any>(
                            frame.getString("address"),
                            frame.getJsonObject("body"),
                            deliveryOptions
                        ).onComplete {
                            if (it.succeeded()) {
                                FrameHelper.sendFrame(
                                    BridgeEventType.SEND.name.toLowerCase(),
                                    frame.getString("replyAddress"),
                                    JsonObject.mapFrom(it.result().body()),
                                    socket.result()
                                )
                            } else {
                                FrameHelper.sendFrame(
                                    BridgeEventType.SEND.name.toLowerCase(),
                                    frame.getString("replyAddress"),
                                    JsonObject.mapFrom(it.cause()),
                                    socket.result()
                                )
                            }
                        }
                    } else {
                        vertx.eventBus()
                            .send("local." + frame.getString("address"), frame.getJsonObject("body"))
                    }
                } else {
                    throw UnsupportedOperationException(frame.toString())
                }
            }
            socket.result().handler(parser)

            //send processor connected status
            val replyAddress = UUID.randomUUID().toString()
            val pc = ProcessorConnection(INSTANCE_ID, System.currentTimeMillis())
            val consumer: MessageConsumer<Boolean> = vertx.eventBus().localConsumer("local.$replyAddress")
            consumer.handler {
                //todo: something with the bool?

                //todo: this is hacky. ServiceBinder.register is supposed to do this
                //register services
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.toLowerCase(),
                    ProcessorAddress.LOGGING_PROCESSOR.address,
                    JsonObject(),
                    tcpSocket
                )

                //register settings
                FrameHelper.sendFrame(
                    BridgeEventType.REGISTER.name.toLowerCase(),
                    ProcessorAddress.SET_LOG_PUBLISH_RATE_LIMIT.address,
                    JsonObject(),
                    tcpSocket
                )

                //deploy processor
                log.info("Deploying source processor")
                vertx.deployVerticle(LogSummaryProcessorVerticle()) {
                    if (it.succeeded()) {
                        processorVerticleId = it.result()
                    } else {
                        log.error("Failed to deploy source processor", it.cause())
                        exitProcess(-1)
                    }
                }
                consumer.unregister()
            }
            FrameHelper.sendFrame(
                BridgeEventType.SEND.name.toLowerCase(), PlatformAddress.PROCESSOR_CONNECTED.address,
                replyAddress, JsonObject(), true, JsonObject.mapFrom(pc), socket.result()
            )
        }
    }

    private fun republishEvents(vertx: Vertx, address: String) {
        vertx.eventBus().localConsumer<JsonObject>(address) {
            if (log.isTraceEnabled) log.trace("Republishing {} to {}", it.body(), address)
            FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name.toLowerCase(),
                address, null, JsonObject(), true, it.body(), tcpSocket
            )
        }
    }
}
