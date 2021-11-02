package spp.processor.live.impl.view

import com.sourceplusplus.protocol.artifact.trace.Trace
import com.sourceplusplus.protocol.artifact.trace.TraceSpan
import com.sourceplusplus.protocol.artifact.trace.TraceSpanLogEntry
import com.sourceplusplus.protocol.artifact.trace.TraceSpanRef
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListener
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListenerFactory
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.EntryAnalysisListener
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.slf4j.LoggerFactory
import spp.processor.InstrumentProcessor
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder

class LiveTracesView(private val subscriptionCache: MetricTypeSubscriptionCache) :
    AnalysisListenerFactory, EntryAnalysisListener {

    companion object {
        private val log = LoggerFactory.getLogger(LiveTracesView::class.java)

        private val formatter = DateTimeFormatterBuilder()
            .appendPattern("yyyyMMddHHmm")
            .toFormatter()
            .withZone(ZoneOffset.UTC)
    }

    override fun build() = Unit
    override fun containsPoint(point: AnalysisListener.Point): Boolean = point == AnalysisListener.Point.Entry
    override fun parseEntry(span: SpanObject, segment: SegmentObject) {
        if (log.isTraceEnabled) log.trace("Parsing span {} of {}", span, segment)

        val entityId = span.operationName
        val subbedArtifacts = subscriptionCache["endpoint_traces"]
        if (subbedArtifacts != null) {
            val subs = subbedArtifacts[entityId]
            if (!subs.isNullOrEmpty()) {
                val entrySpan = TraceSpan(
                    segment.traceId,
                    segment.traceSegmentId,
                    span.spanId,
                    span.parentSpanId,
                    span.refsList.map {
                        TraceSpanRef(
                            it.traceId,
                            it.parentTraceSegmentId,
                            it.parentSpanId,
                            it.refType.name
                        )
                    },
                    segment.service,
                    segment.serviceInstance,
                    Instant.ofEpochMilli(span.startTime).toKotlinInstant(),
                    Instant.ofEpochMilli(span.endTime).toKotlinInstant(),
                    entityId,
                    subs.first().subscription.artifactQualifiedName,
                    span.spanType.name,
                    span.peer,
                    span.componentId.toString(),
                    span.isError,
                    false,
                    false,
                    span.spanLayer.name,
                    span.tagsList.associate { it.key to it.value },
                    span.logsList.flatMap { log -> log.dataList.map { Pair(log.time, it.value) } }
                        .map { TraceSpanLogEntry(Instant.ofEpochMilli(it.first).toKotlinInstant(), it.second) }
                )
                val trace = Trace(
                    segment.traceId,
                    listOf(span.operationName),
                    (span.endTime - span.startTime).toInt(),
                    Instant.ofEpochMilli(span.startTime).toKotlinInstant(),
                    span.isError,
                    listOf(segment.traceId),
                    false,
                    segment.traceSegmentId,
                    span.tagsList.map { it.key to it.value }.toMap()
                        .toMutableMap().apply { put("entrySpan", Json.encode(entrySpan)) }
                )

                subs.forEach { sub ->
                    val event = JsonObject()
                        .put("type", "TRACES")
                        .put("multiMetrics", false)
                        .put("artifactQualifiedName", sub.subscription.artifactQualifiedName)
                        .put("entityId", entityId)
                        .put("timeBucket", formatter.format(trace.start.toJavaInstant()))
                        .put("trace", JsonObject.mapFrom(trace))
                    InstrumentProcessor.vertx.eventBus().send(sub.consumer.address(), event)
                }
            }
        }
    }

    override fun create(p0: ModuleManager, p1: AnalyzerModuleConfig): AnalysisListener = this
}
