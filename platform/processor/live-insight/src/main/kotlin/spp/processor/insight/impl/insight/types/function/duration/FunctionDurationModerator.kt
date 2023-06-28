/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
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
package spp.processor.insight.impl.insight.types.function.duration

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import mu.KotlinLogging
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListener
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListenerFactory
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.EntryAnalysisListener
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.LocalAnalysisListener
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.telemetry.api.MetricsTag
import spp.jetbrains.artifact.service.getFunctions
import spp.jetbrains.marker.service.getFullyQualifiedName
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.insight.InsightProcessor.workspaceQueue
import spp.processor.insight.impl.environment.InsightEnvironment
import spp.processor.insight.impl.insight.LiveMetricProcessor
import spp.processor.insight.impl.moderate.InsightModerator
import spp.processor.insight.impl.moderate.model.LiveInsightRequest
import spp.processor.insight.impl.moderate.model.UniqueMeterName
import spp.processor.view.ViewProcessor
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.insight.InsightType
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.view.rule.ViewRule
import java.time.Instant
import java.util.*

/**
 * Moderates the following insights:
 * - [InsightType.FUNCTION_DURATION]
 */
class FunctionDurationModerator : InsightModerator(),
    LocalAnalysisListener, EntryAnalysisListener, AnalysisListenerFactory {

    private val log = KotlinLogging.logger {}
    override val type: InsightType = InsightType.FUNCTION_DURATION

    override fun create(p0: ModuleManager, p1: AnalyzerModuleConfig) = this
    override fun build() = Unit
    override fun containsPoint(point: AnalysisListener.Point): Boolean =
        point == AnalysisListener.Point.Local || point == AnalysisListener.Point.Entry

    override fun parseEntry(span: SpanObject, segment: SegmentObject) = parseSpan(span)
    override fun parseLocal(span: SpanObject, segment: SegmentObject) = parseSpan(span)

    /**
     * Inspects SkyWalking traces for function durations and calculates the average duration of the function.
     *
     * Note: Functions found via this inspector will have their [InsightType.FUNCTION_DURATION] priority reset
     * to 0 to avoid unnecessarily creating [LiveInsightRequest] to gather more data.
     */
    private fun parseSpan(span: SpanObject) {
        val gauge = LiveMetricProcessor.getGauge(
            UniqueMeterName(
                InsightType.FUNCTION_DURATION,
                MetricsTag.Keys(),
                MetricsTag.Values(),
                span.operationName.substringBefore("(")
            )
        )
        gauge.value = span.endTime - span.startTime

        //todo: methods with spans don't need instrument moderation, reset priority to 0, remove from queue if present
    }

    override suspend fun start() {
        super.start()

        ViewProcessor.liveViewService.meterView.subscribe { metrics ->
            val metricsName = metrics.getJsonObject("meta").getString("metricsName") ?: return@subscribe
            if (!metricsName.startsWith("spp_")) return@subscribe

            val rawMetrics = JsonObject.mapFrom(metrics)
            val meterId = metricsName.substringAfter("spp_method_timer_").substringBefore("_avg")
            val insightRequest = workspaceQueue.get(meterId) ?: return@subscribe

            val meter = insightRequest.liveInstrument as LiveMeter
            val value = rawMetrics.getLong("value")
            val operationName = meter.location.source
            SourceStorage.put("${InsightType.FUNCTION_DURATION}:$operationName", value)
            log.debug("Added function duration $value to $operationName")
        }
    }

    override fun postSetupInsight(request: LiveInsightRequest) {
        val liveMeter = request.liveInstrument as LiveMeter
        ViewProcessor.liveViewService.saveRuleIfAbsent(
            ViewRule(
                "${liveMeter.id}_avg",
                buildString {
                    append("(")
                    append(liveMeter.id).append("_timer_duration_sum")
                    append("/")
                    append(liveMeter.id).append("_timer_meter")
                    append(").avg(['service']).service(['service'], Layer.GENERAL)")
                }
            )
        ).onFailure {
            log.error("Failed to save rule for ${liveMeter.id}_avg", it)
        }
        ViewProcessor.liveViewService.saveRuleIfAbsent(
            ViewRule(
                "${liveMeter.id}_count",
                buildString {
                    append("(")
                    append(liveMeter.id).append("_timer_meter")
                    append(").sum(['service']).service(['service'], Layer.GENERAL)")
                }
            )
        ).onFailure {
            log.error("Failed to save rule for ${liveMeter.id}_count", it)
        }
    }

    override suspend fun addAvailableInsights(
        psiFile: PsiFile,
        artifact: ArtifactQualifiedName,
        insights: JsonObject
    ) {
        val function = psiFile.getFunctions().find {
            it.getFullyQualifiedName().identifier == artifact.identifier
        }!!
        val durationInsights = getInsights(function)

        if (durationInsights.isEmpty) {
            //increase priority since we have no insights
            SourceStorage.counter("${InsightType.FUNCTION_DURATION}:${artifact.identifier}").addAndGet(100)
        } else {
            insights.put(InsightType.FUNCTION_DURATION.name, durationInsights)
        }
    }

    override suspend fun searchProject(environment: InsightEnvironment) {
        //iterate over all functions in the project
        environment.getAllFunctions().forEach { function ->
            val qualifiedName = function.getFullyQualifiedName()
            log.trace { "Checking {} for function duration insights".args(qualifiedName) }

            val insights = getInsights(function)
            val duration = ((insights.list.firstOrNull() as? JsonObject)?.map?.values?.first() as? Long)?.let {
                OptionalLong.of(it)
            } ?: OptionalLong.empty()

            var insightPriority = SourceStorage.counter(
                "${InsightType.FUNCTION_DURATION}:${qualifiedName.identifier}"
            ).get().await()
            if (duration.isEmpty) {
                //no function duration found, increase priority
                insightPriority = SourceStorage.counter("${InsightType.FUNCTION_DURATION}:${qualifiedName.identifier}")
                    .addAndGet(1).await()
                log.trace(
                    "No function duration found for {}. Increased priority to {}",
                    qualifiedName, insightPriority
                )
            } else {
                log.debug("Found function duration of {} for {}", duration.asLong, qualifiedName)
            }

            var metricId = "spp_" + ("insight-function-duration:" + qualifiedName.identifier)
                .replace("[^a-zA-Z0-9]".toRegex(), "_")

            //remove trailing '_' characters
            var lastChar = metricId.reversed().indexOfFirst { it != '_' }
            if (lastChar == -1) lastChar = metricId.length
            metricId = metricId.substring(0, metricId.length - lastChar)

            offerQueue.add(
                LiveInsightRequest(
                    LiveMeter(
                        id = metricId,
                        meterType = MeterType.METHOD_TIMER,
                        metricValue = MetricValue(MetricValueType.NUMBER, "1"),
                        location = LiveSourceLocation(qualifiedName.identifier)
                    ),
                    this,
                    insightPriority,
                    Instant.now()
                )
            )
        }
    }

    private suspend fun getInsights(function: PsiNamedElement): JsonArray {
        val durationInsights = JsonArray()

        val qualifiedName = function.getFullyQualifiedName().identifier
        SourceStorage.get<Long>("${InsightType.FUNCTION_DURATION}:$qualifiedName")?.let { duration ->
            durationInsights.add(JsonObject().put(qualifiedName, duration))
            log.debug("Function: $qualifiedName - Total duration: $duration ms")
        }

//        val fileMarker = SourceFileMarker(function.containingFile)
//        val guideMark = MethodGuideMark(fileMarker, function as PsiMethod)
//        JVMEndpointDetector(function.project).determineEndpointName(guideMark).await().forEach {
//            SourceStorage.get<Long>("${InsightType.FUNCTION_DURATION}:${it.name}")?.let { duration ->
//                durationInsights.add(JsonObject().put(it.toString(), duration))
//                log.debug("Endpoint: ${it.name} - Total duration: $duration ms")
//            }
//        }

        return durationInsights
    }
}
