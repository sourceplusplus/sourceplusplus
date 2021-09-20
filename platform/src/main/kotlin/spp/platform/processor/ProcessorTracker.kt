package spp.platform.processor

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.SourcePlatform
import spp.protocol.platform.PlatformAddress
import spp.protocol.processor.status.ProcessorConnection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ProcessorTracker : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}

    //only stores connection time; SourceServiceDiscovery handles registered services
    private val activeProcessors: MutableMap<String, Instant> = ConcurrentHashMap()

    override suspend fun start() {
        vertx.eventBus().consumer<JsonObject>(connectedProcessorsAddress) {
            GlobalScope.launch(vertx.dispatcher()) {
                it.reply(
                    vertx.sharedData().getLocalCounter(
                        PlatformAddress.PROCESSOR_CONNECTED.address
                    ).await().get().await()
                )
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROCESSOR_CONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), ProcessorConnection::class.java)
            val latency = System.currentTimeMillis() - conn.connectionTime
            log.trace { "Establishing connection with processor ${conn.processorId}" }

            activeProcessors[conn.processorId] = Instant.now()
            it.reply(true)

            log.info(
                "Processor connection established. Latency: {}ms - Active processors: {}",
                latency, activeProcessors.size
            )

            GlobalScope.launch(vertx.dispatcher()) {
                vertx.sharedData().getLocalCounter(PlatformAddress.PROCESSOR_CONNECTED.address).await()
                    .incrementAndGet().await()
            }
        }
        vertx.eventBus().consumer<JsonObject>(PlatformAddress.PROCESSOR_DISCONNECTED.address) {
            val conn = Json.decodeValue(it.body().toString(), ProcessorConnection::class.java)
            val connectedAt = activeProcessors.remove(conn.processorId)!!
            log.info("Processor disconnected. Connection time: {}", Duration.between(Instant.now(), connectedAt))

            GlobalScope.launch(vertx.dispatcher()) {
                SourcePlatform.discovery.getRecords { true }.await().forEach {
                    if (it.metadata.getString("INSTANCE_ID") == conn.processorId) {
                        SourcePlatform.discovery.unpublish(it.registration)
                    }
                }

                vertx.sharedData().getLocalCounter(PlatformAddress.PROCESSOR_CONNECTED.address).await()
                    .decrementAndGet().await()
            }
        }
    }

    companion object {
        private const val connectedProcessorsAddress = "get-connected-processors"

        suspend fun getConnectedProcessors(vertx: Vertx): Int {
            return vertx.eventBus().request<Int>(connectedProcessorsAddress, null).await().body()
        }
    }
}
