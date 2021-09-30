package spp.processor

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import kotlinx.datetime.Instant
import org.apache.skywalking.oap.server.core.analysis.metrics.DataTable
import org.slf4j.LoggerFactory
import spp.processor.SourceProcessor.INSTANCE_ID
import spp.processor.live.LiveInstrumentProcessor
import spp.processor.live.LiveViewProcessor
import spp.processor.live.impl.LiveInstrumentProcessorImpl
import spp.processor.live.impl.LiveViewProcessorImpl
import spp.processor.logging.LoggingProcessor
import spp.processor.logging.impl.LoggingProcessorImpl
import spp.protocol.processor.ProcessorAddress
import kotlin.system.exitProcess

class SourceProcessorVerticle : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(SourceProcessorVerticle::class.java)

        val loggingProcessor = LoggingProcessorImpl()
        val liveViewProcessor = LiveViewProcessorImpl()
        val liveInstrumentProcessor = LiveInstrumentProcessorImpl()
    }

    private var loggingRecord: Record? = null
    private var liveInstrumentRecord: Record? = null
    private var liveViewRecord: Record? = null

    override suspend fun start() {
        log.info("Starting SourceProcessorVerticle")
        val module = SimpleModule()
        module.addSerializer(DataTable::class.java, object : JsonSerializer<DataTable>() {
            override fun serialize(value: DataTable, gen: JsonGenerator, provider: SerializerProvider) {
                val data = mutableMapOf<String, Long>()
                value.keys().forEach { data[it] = value.get(it) }
                gen.writeStartObject()
                data.forEach {
                    gen.writeNumberField(it.key, it.value)
                }
                gen.writeEndObject()
            }
        })
        DatabindCodec.mapper().registerModule(module)

        module.addSerializer(Instant::class.java, KSerializers.KotlinInstantSerializer())
        module.addDeserializer(Instant::class.java, KSerializers.KotlinInstantDeserializer())
        DatabindCodec.mapper().registerModule(module)

        vertx.deployVerticle(loggingProcessor).await()
        vertx.deployVerticle(liveViewProcessor).await()
        vertx.deployVerticle(liveInstrumentProcessor).await()

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .setAddress(ProcessorAddress.LOGGING_PROCESSOR.address)
            .register(LoggingProcessor::class.java, loggingProcessor)
        loggingRecord = EventBusService.createRecord(
            ProcessorAddress.LOGGING_PROCESSOR.address,
            ProcessorAddress.LOGGING_PROCESSOR.address,
            LoggingProcessor::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        SourceProcessor.discovery.publish(loggingRecord) {
            if (it.succeeded()) {
                log.info("Logging processor published")
            } else {
                log.error("Failed to publish logging processor", it.cause())
                exitProcess(-1)
            }
        }

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .setAddress(ProcessorAddress.LIVE_INSTRUMENT_PROCESSOR.address)
            .register(LiveInstrumentProcessor::class.java, liveInstrumentProcessor)
        liveInstrumentRecord = EventBusService.createRecord(
            ProcessorAddress.LIVE_INSTRUMENT_PROCESSOR.address,
            ProcessorAddress.LIVE_INSTRUMENT_PROCESSOR.address,
            LiveInstrumentProcessor::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        SourceProcessor.discovery.publish(liveInstrumentRecord) {
            if (it.succeeded()) {
                log.info("Live instrument processor published")
            } else {
                log.error("Failed to publish live instrument processor", it.cause())
                exitProcess(-1)
            }
        }

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .setAddress(ProcessorAddress.LIVE_VIEW_PROCESSOR.address)
            .register(LiveViewProcessor::class.java, liveViewProcessor)
        liveViewRecord = EventBusService.createRecord(
            ProcessorAddress.LIVE_VIEW_PROCESSOR.address,
            ProcessorAddress.LIVE_VIEW_PROCESSOR.address,
            LiveViewProcessor::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        SourceProcessor.discovery.publish(liveViewRecord) {
            if (it.succeeded()) {
                log.info("Live view processor published")
            } else {
                log.error("Failed to publish live view processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun stop() {
        log.info("Stopping SourceProcessorVerticle")
        SourceProcessor.discovery.unpublish(loggingRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Logging processor unpublished")
            } else {
                log.error("Failed to unpublish logging processor", it.cause())
            }
        }.await()
        SourceProcessor.discovery.unpublish(liveInstrumentRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live instrument processor unpublished")
            } else {
                log.error("Failed to unpublish live instrument processor", it.cause())
            }
        }.await()
        SourceProcessor.discovery.unpublish(liveViewRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live view processor unpublished")
            } else {
                log.error("Failed to unpublish live view processor", it.cause())
            }
        }.await()
    }

    class KSerializers {
        /**
         * Used to serialize [Instant] classes.
         *
         * @since 0.1.0
         */
        class KotlinInstantSerializer : JsonSerializer<Instant>() {
            override fun serialize(value: Instant, jgen: JsonGenerator, provider: SerializerProvider) =
                jgen.writeNumber(value.toEpochMilliseconds())
        }

        /**
         * Used to deserialize [Instant] classes.
         *
         * @since 0.1.0
         */
        class KotlinInstantDeserializer : JsonDeserializer<Instant>() {
            override fun deserialize(p: JsonParser, p1: DeserializationContext): Instant =
                Instant.fromEpochMilliseconds((p.codec.readTree(p) as JsonNode).longValue())
        }
    }
}
