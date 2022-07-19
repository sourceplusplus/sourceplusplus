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
package spp.processor.live.impl.view

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
import spp.platform.common.FeedbackProcessor
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.protocol.artifact.trace.Trace
import spp.protocol.artifact.trace.TraceSpan
import spp.protocol.artifact.trace.TraceSpanLogEntry
import spp.protocol.artifact.trace.TraceSpanRef
import java.net.URI
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
                    span.tagsList.associate { it.key to it.value }
                        .toMutableMap().apply { put("entrySpan", Json.encode(entrySpan)) }
                )

                //add trace meta
                val url = entrySpan.tags["url"]
                val httpMethod = entrySpan.tags["http.method"]
                if (url != null && httpMethod != null) {
                    val resolvedEndpointName = "$httpMethod:${URI(url).path}"
                    trace.meta["resolvedEndpointName"] = resolvedEndpointName
                }

                subs.forEach { sub ->
                    val event = JsonObject()
                        .put("type", "TRACES")
                        .put("multiMetrics", false)
                        .put("artifactQualifiedName", JsonObject.mapFrom(sub.subscription.artifactQualifiedName))
                        .put("entityId", entityId)
                        .put("timeBucket", formatter.format(trace.start.toJavaInstant()))
                        .put("trace", JsonObject.mapFrom(trace))
                    FeedbackProcessor.vertx.eventBus().send(sub.consumer.address(), event)
                }
            }
        }
    }

    override fun create(p0: ModuleManager, p1: AnalyzerModuleConfig): AnalysisListener = this
}
