package spp.processor.live.impl.instrument

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.protobuf.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject
import org.apache.skywalking.apm.network.logging.v3.LogData
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListener
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListenerFactory
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.*
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO
import org.slf4j.LoggerFactory
import spp.processor.InstrumentProcessor
import spp.protocol.processor.ProcessorAddress
import spp.protocol.processor.ProcessorAddress.BREAKPOINT_HIT
import java.util.concurrent.TimeUnit

class LiveInstrumentAnalysis(elasticSearch: EsDAO) : AnalysisListenerFactory, LogAnalysisListenerFactory {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentAnalysis::class.java)

        const val LOCAL_VARIABLE = "spp.local-variable:"
        const val STATIC_FIELD = "spp.static-field:"
        const val INSTANCE_FIELD = "spp.field:"
        const val STACK_TRACE = "spp.stack-trace:"
        const val BREAKPOINT = "spp.breakpoint:"
    }

    private var logPublishRateLimit = 1000
    private val logPublishCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build(object : CacheLoader<String, Long>() {
            override fun load(key: String): Long = -1
        })

    init {
        //todo: map of rate limit per log id
        InstrumentProcessor.vertx.eventBus().consumer<Int>(ProcessorAddress.SET_LOG_PUBLISH_RATE_LIMIT.address) {
            logPublishRateLimit = it.body()
        }
    }

    private val sppLogAnalyzer = object : LogAnalysisListener {
        override fun build() = Unit

        override fun parse(logData: LogData.Builder, p1: Message?): LogAnalysisListener {
            if (log.isTraceEnabled) log.trace("Parsing log data {}", logData)
            var logId: String? = null
            var logger: String? = null
            var thread: String? = null
            val arguments = JsonArray()
            logData.tags.dataList.forEach {
                when {
                    "log_id" == it.key -> logId = it.value
                    "logger" == it.key -> logger = it.value
                    "thread" == it.key -> thread = it.value
                    it.key.startsWith("argument.") -> arguments.add(it.value)
                }
            }
            if (logId == null) return this

            val logLastPublished = logPublishCache.get(logId!!)
            if (System.currentTimeMillis() - logLastPublished < logPublishRateLimit) {
                return this
            }

            val logHit = JsonObject()
                .put("logId", logId)
                .put("occurredAt", logData.timestamp)
                .put("serviceInstance", logData.serviceInstance)
                .put("service", logData.service)
                .put(
                    "logResult",
                    JsonObject()
                        .put("orderType", "NEWEST_LOGS")
                        .put("timestamp", logData.timestamp)
                        .put(
                            "logs", JsonArray().add(
                                JsonObject()
                                    .put("timestamp", logData.timestamp)
                                    .put("content", logData.body.text.text)
                                    .put("level", "Live")
                                    .put("logger", logger)
                                    .put("thread", thread)
                                    .put("arguments", arguments)
                            )
                        )
                        .put("total", -1)
                )
            InstrumentProcessor.vertx.eventBus().publish(ProcessorAddress.LOG_HIT.address, logHit)
            logPublishCache.put(logId!!, System.currentTimeMillis())
            return this
        }
    }

    override fun create() = sppLogAnalyzer

    private class Listener(private val elasticSearch: EsDAO) :
        LocalAnalysisListener, EntryAnalysisListener, ExitAnalysisListener {
        override fun build() = Unit
        override fun containsPoint(point: AnalysisListener.Point): Boolean =
            point == AnalysisListener.Point.Local || point == AnalysisListener.Point.Entry ||
                    point == AnalysisListener.Point.Exit

        override fun parseExit(span: SpanObject, segment: SegmentObject) = parseSpan(span, segment)
        override fun parseEntry(span: SpanObject, segment: SegmentObject) = parseSpan(span, segment)
        override fun parseLocal(span: SpanObject, segment: SegmentObject) = parseSpan(span, segment)

        fun parseSpan(span: SpanObject, segment: SegmentObject) {
            if (log.isTraceEnabled) log.trace("Parsing span {} of {}", span, segment)
            val locationSources = mutableMapOf<String, String>()
            val locationLines = mutableMapOf<String, Int>()
            val variables = mutableMapOf<String, MutableList<MutableMap<String, Any>>>()
            val stackTraces = mutableMapOf<String, String>()
            val breakpointIds = mutableListOf<String>()
            span.tagsList.forEach {
                when {
                    it.key.startsWith(LOCAL_VARIABLE) -> {
                        val parts = it.key.substring(LOCAL_VARIABLE.length).split(":")
                        val breakpointId = parts[0]
                        variables.putIfAbsent(breakpointId, mutableListOf())
                        variables[breakpointId]!!.add(
                            mutableMapOf(
                                "scope" to "LOCAL_VARIABLE",
                                "data" to mutableMapOf(parts[1] to it.value)
                            )
                        )
                    }
                    it.key.startsWith(STATIC_FIELD) -> {
                        val parts = it.key.substring(STATIC_FIELD.length).split(":")
                        val breakpointId = parts[0]
                        variables.putIfAbsent(breakpointId, mutableListOf())
                        variables[breakpointId]!!.add(
                            mutableMapOf(
                                "scope" to "STATIC_FIELD",
                                "data" to mutableMapOf(parts[1] to it.value)
                            )
                        )
                    }
                    it.key.startsWith(INSTANCE_FIELD) -> {
                        val parts = it.key.substring(INSTANCE_FIELD.length).split(":")
                        val breakpointId = parts[0]
                        variables.putIfAbsent(breakpointId, mutableListOf())
                        variables[breakpointId]!!.add(
                            mutableMapOf(
                                "scope" to "INSTANCE_FIELD",
                                "data" to mutableMapOf(parts[1] to it.value)
                            )
                        )
                    }
                    it.key.startsWith(STACK_TRACE) -> {
                        stackTraces[it.key.substring(STACK_TRACE.length)] = it.value
                    }
                    it.key.startsWith(BREAKPOINT) -> {
                        val breakpointId = it.key.substring(BREAKPOINT.length)
                        breakpointIds.add(breakpointId)
                        JsonObject(it.value).apply {
                            locationSources[breakpointId] = getString("source")
                            locationLines[breakpointId] = getInteger("line")
                        }
                    }
                }
            }

            breakpointIds.forEach {
                val bpHitObj = mapOf(
                    "breakpoint_id" to it,
                    "trace_id" to segment.traceId,
                    "stack_trace" to stackTraces[it]!!,
                    "variables" to variables.getOrDefault(it, emptyList()),
                    "occurred_at" to span.startTime,
                    "service_host" to segment.serviceInstance.substringAfter("@"),
                    "service" to segment.service,
                    "location_source" to locationSources[it]!!,
                    "location_line" to locationLines[it]!!
                )
                elasticSearch.client.forceInsert("spp_breakpoint_hit", "${it}:${segment.traceId}", bpHitObj)
                InstrumentProcessor.vertx.eventBus().publish(BREAKPOINT_HIT.address, JsonObject.mapFrom(bpHitObj))
            }
        }
    }

    private val listener: AnalysisListener = Listener(elasticSearch)
    override fun create(p0: ModuleManager, p1: AnalyzerModuleConfig): AnalysisListener = listener
}
