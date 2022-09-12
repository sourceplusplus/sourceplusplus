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
package spp.processor.live.impl.instrument

import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import net.bytebuddy.jar.asm.Type
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.*
import org.apache.skywalking.oap.server.core.query.TraceQueryService
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.processor.InstrumentProcessor.liveInstrumentProcessor
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.artifact.exception.LiveStackTraceElement
import spp.protocol.artifact.exception.sourceAsLineNumber
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.variable.LiveVariable
import spp.protocol.instrument.variable.LiveVariableScope
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class LiveBreakpointAnalyzer(
    private val traceQueryService: TraceQueryService
) : LocalAnalysisListener, EntryAnalysisListener, ExitAnalysisListener, AnalysisListenerFactory {

    override fun build() = Unit
    override fun containsPoint(point: AnalysisListener.Point): Boolean =
        point == AnalysisListener.Point.Local || point == AnalysisListener.Point.Entry ||
                point == AnalysisListener.Point.Exit

    override fun parseExit(span: SpanObject, segment: SegmentObject) = parseSpan(span, segment)
    override fun parseEntry(span: SpanObject, segment: SegmentObject) = parseSpan(span, segment)
    override fun parseLocal(span: SpanObject, segment: SegmentObject) = parseSpan(span, segment)

    private fun parseSpan(span: SpanObject, segment: SegmentObject) {
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

                it.key.startsWith(GLOBAL_VARIABLE) -> {
                    val parts = it.key.substring(GLOBAL_VARIABLE.length).split(":")
                    val breakpointId = parts[0]
                    variables.putIfAbsent(breakpointId, mutableListOf())
                    variables[breakpointId]!!.add(
                        mutableMapOf(
                            "scope" to "GLOBAL_VARIABLE",
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
                "service_instance" to segment.serviceInstance,
                "service" to segment.service,
                "location_source" to locationSources[it]!!,
                "location_line" to locationLines[it]!!
            )
            handleBreakpointHit(transformRawBreakpointHit(JsonObject.mapFrom(bpHitObj)))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LiveBreakpointAnalyzer::class.java)

        const val LOCAL_VARIABLE = "spp.local-variable:"
        const val GLOBAL_VARIABLE = "spp.global-variable:"
        const val STATIC_FIELD = "spp.static-field:"
        const val INSTANCE_FIELD = "spp.field:"
        const val STACK_TRACE = "spp.stack-trace:"
        const val BREAKPOINT = "spp.breakpoint:"

        private fun toLiveVariable(varName: String, scope: LiveVariableScope?, varData: JsonObject): LiveVariable {
            val liveClass = varData.getString("@class")
            val liveIdentity = getLiveIdentity(varData)
            if (varData.containsKey("@skip")) {
                return LiveVariable(varName, varData, scope = scope, liveClazz = liveClass, liveIdentity = liveIdentity)
            } else if (varData.containsKey("@ref")) {
                //no need to transform the value, it's a reference
                return LiveVariable(varName, null, scope = scope, liveClazz = liveClass, liveIdentity = liveIdentity)
            }

            val innerVars = mutableListOf<LiveVariable>()
            varData.fieldNames().forEach {
                if (!it.startsWith("@")) {
                    if (varData.get<Any>(it) is JsonObject) {
                        innerVars.add(toLiveVariable(it, null, varData.getJsonObject(it)))
                    } else if (varData.get<Any>(it) is JsonArray) {
                        innerVars.add(toLiveVariableArray(it, null, varData.getJsonArray(it)))
                    } else {
                        innerVars.add(LiveVariable(it, varData[it]))
                    }
                }
            }
            return LiveVariable(varName, innerVars, scope = scope, liveClazz = liveClass, liveIdentity = liveIdentity)
        }

        fun toLiveVariableArray(varName: String, scope: LiveVariableScope?, varData: JsonArray): LiveVariable {
            val innerVars = mutableListOf<LiveVariable>()
            varData.forEachIndexed { index, it ->
                if (it is JsonObject) {
                    innerVars.add(toLiveVariable("$index", null, it))
                } else {
                    innerVars.add(LiveVariable("$index", it))
                }
            }
            return LiveVariable(varName, innerVars, scope = scope)
        }

        fun transformRawBreakpointHit(bpData: JsonObject): LiveBreakpointHit {
            if (log.isTraceEnabled) log.trace("Transforming raw breakpoint hit: {}", bpData)
            val varDatum = bpData.getJsonArray("variables")
            val variables = mutableListOf<LiveVariable>()
            var thisVar: LiveVariable? = null
            for (i in varDatum.list.indices) {
                val varData = varDatum.getJsonObject(i)
                val varName = varData.getJsonObject("data").fieldNames().first()
                val outerVal = JsonObject(varData.getJsonObject("data").getString(varName))
                val scope = LiveVariableScope.valueOf(varData.getString("scope"))

                var liveVar = if (outerVal.get<Any>(varName) is JsonObject) {
                    toLiveVariable(varName, scope, outerVal.getJsonObject(varName))
                } else if (outerVal.get<Any>(varName) is JsonArray) {
                    val liveArr = toLiveVariableArray(varName, scope, outerVal.getJsonArray(varName))
                    try {
                        liveArr.copy(
                            liveClazz = Type.getType(outerVal.getString("@class")).className,
                            liveIdentity = getLiveIdentity(outerVal)
                        )
                    } catch (ignore: IllegalArgumentException) {
                        liveArr.copy(
                            liveClazz = outerVal.getString("@class"),
                            liveIdentity = getLiveIdentity(outerVal)
                        )
                    }
                } else {
                    LiveVariable(
                        varName,
                        outerVal[varName],
                        scope = scope,
                        liveClazz = outerVal.getString("@class"),
                        liveIdentity = getLiveIdentity(outerVal)
                    )
                }
                if (liveVar.liveIdentity == null && outerVal.containsKey("@id")) {
                    liveVar = liveVar.copy(liveIdentity = outerVal.getString("@id"))
                }
                liveVar = liveVar.copy(presentation = LiveVariablePresentation.format(liveVar.liveClazz, liveVar))
                variables.add(liveVar)

                if (liveVar.name == "this") {
                    thisVar = liveVar
                }
            }

            //put instance variables in "this"
            if (thisVar?.value is List<*>) {
                val thisVariables = thisVar.value as MutableList<LiveVariable>?
                variables.filter { it.scope == LiveVariableScope.INSTANCE_FIELD }.forEach { v ->
                    thisVariables?.removeIf { rem ->
                        if (rem.name == v.name) {
                            variables.removeIf { it.name == v.name }
                            true
                        } else {
                            false
                        }
                    }
                    thisVariables?.add(v)
                }
            }

            val stackTrace = LiveStackTrace.fromString(bpData.getString("stack_trace"))!!
            //correct unknown source
            if (stackTrace.first().sourceAsLineNumber() == null) {
                val language = stackTrace.elements[1].source.substringAfter(".").substringBefore(":")
                val actualSource = "${
                    bpData.getString("location_source").substringAfterLast(".")
                }.$language:${bpData.getInteger("location_line")}"
                val correctedElement = LiveStackTraceElement(stackTrace.first().method, actualSource)
                stackTrace.elements.removeAt(0)
                stackTrace.elements.add(0, correctedElement)
            }
            //add live variables
            stackTrace.first().variables.addAll(variables)

            return LiveBreakpointHit(
                bpData.getString("breakpoint_id"),
                bpData.getString("trace_id"),
                Instant.ofEpochMilli(bpData.getLong("occurred_at")),
                bpData.getString("service_instance"),
                bpData.getString("service"),
                stackTrace
            )
        }

        private fun getLiveIdentity(varData: JsonObject): String? {
            return varData.getString("@id") ?: varData.getString("@identity") ?: varData.getString("@ref")
        }
    }

    private fun handleBreakpointHit(hit: LiveBreakpointHit) {
        if (log.isTraceEnabled) log.trace("Live breakpoint hit: {}", hit)
        val value = traceQueryService.queryTrace(hit.traceId)
        if (value.spans.isNotEmpty()) {
            println(value)
        } else {
            println("No trace found for trace id: ${hit.traceId}")
        }
        val liveInstrument = liveInstrumentProcessor._getDeveloperInstrumentById(hit.breakpointId)
        if (liveInstrument != null) {
            val instrumentMeta = liveInstrument.instrument.meta as MutableMap<String, Any>
            if ((instrumentMeta["hit_count"] as AtomicInteger?)?.incrementAndGet() == 1) {
                instrumentMeta["first_hit_at"] = System.currentTimeMillis().toString()
            }
            instrumentMeta["last_hit_at"] = System.currentTimeMillis().toString()
        }

        val devInstrument = liveInstrument ?: liveInstrumentProcessor.getCachedDeveloperInstrument(hit.breakpointId)
        ClusterConnection.getVertx().eventBus().publish(
            toLiveInstrumentSubscriberAddress(devInstrument.developerAuth.selfId),
            JsonObject.mapFrom(LiveInstrumentEvent(LiveInstrumentEventType.BREAKPOINT_HIT, Json.encode(hit)))
        )
        if (log.isTraceEnabled) log.trace("Published live breakpoint hit")
    }

    override fun create(p0: ModuleManager, p1: AnalyzerModuleConfig) = LiveBreakpointAnalyzer(traceQueryService)
}
