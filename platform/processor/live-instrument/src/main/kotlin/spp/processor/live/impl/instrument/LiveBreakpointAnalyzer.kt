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
package spp.processor.live.impl.instrument

import io.grpc.Context
import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.bytebuddy.jar.asm.Type
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.*
import org.apache.skywalking.oap.server.core.query.TraceQueryService
import org.apache.skywalking.oap.server.library.module.ModuleManager
import spp.platform.common.ClusterConnection
import spp.platform.common.util.ContextUtil
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.live.impl.instrument.breakpoint.LiveVariablePresentation
import spp.protocol.artifact.exception.LiveStackTrace
import spp.protocol.artifact.exception.LiveStackTraceElement
import spp.protocol.artifact.exception.sourceAsLineNumber
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType.BREAKPOINT_HIT
import spp.protocol.instrument.variable.LiveVariable
import spp.protocol.instrument.variable.LiveVariableScope
import spp.protocol.platform.auth.DataRedaction
import spp.protocol.platform.auth.RedactionType
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscription
import java.time.Instant
import java.util.regex.Pattern

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
        if (breakpointIds.isEmpty()) {
            return
        }

        val grpcContext = if (Vertx.currentContext() == null) {
            Context.current()
        } else null
        GlobalScope.launch(ClusterConnection.getVertx().dispatcher()) {
            ContextUtil.addToVertx(grpcContext)

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
    }

    companion object {
        private val log = KotlinLogging.logger {}

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

        private fun toLiveVariableArray(varName: String, scope: LiveVariableScope?, varData: JsonArray): LiveVariable {
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
            log.trace { "Transforming raw breakpoint hit: {}".args(bpData) }
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

    private suspend fun handleBreakpointHit(hit: LiveBreakpointHit) {
        log.trace { "Live breakpoint hit: {}".args(hit) }
        val liveInstrument = SourceStorage.getLiveInstrument(hit.breakpointId, true)
        if (liveInstrument != null) {
            val instrumentMeta = liveInstrument.meta as MutableMap<String, Any>
            instrumentMeta["hit_count"] = (instrumentMeta["hit_count"] as Int?)?.plus(1) ?: 1
            if (instrumentMeta["hit_count"] == 1) {
                instrumentMeta["first_hit_at"] = System.currentTimeMillis().toString()
            }
            instrumentMeta["last_hit_at"] = System.currentTimeMillis().toString()
            SourceStorage.updateLiveInstrument(liveInstrument.id!!, liveInstrument)

            val developerId = liveInstrument.meta["spp.developer_id"] as String
            doDataRedactions(SourceStorage.getDeveloperDataRedactions(developerId), hit)

            ClusterConnection.getVertx().eventBus().publish(
                toLiveInstrumentSubscription(hit.breakpointId),
                JsonObject.mapFrom(LiveInstrumentEvent(BREAKPOINT_HIT, Json.encode(hit)))
            )
            //todo: remove dev-specific publish
            ClusterConnection.getVertx().eventBus().publish(
                toLiveInstrumentSubscriberAddress(developerId),
                JsonObject.mapFrom(LiveInstrumentEvent(BREAKPOINT_HIT, Json.encode(hit)))
            )
            log.trace { "Published live breakpoint hit" }
        } else {
            log.warn { "No live instrument found for breakpoint id: ${hit.breakpointId}" }
        }
    }

    private fun doDataRedactions(redactions: List<DataRedaction>, hit: LiveBreakpointHit) {
        if (redactions.isEmpty()) return

        hit.stackTrace.elements.forEach {
            val rawVars = it.variables.toList()
            it.variables.clear()
            it.variables.addAll(redactions.fold(rawVars) { vars, redaction -> redactLiveVariables(redaction, vars) })
        }
    }

    private fun redactLiveVariables(redaction: DataRedaction, vars: List<LiveVariable>): List<LiveVariable> {
        if (vars.isEmpty()) return vars

        val redactedVars = mutableListOf<LiveVariable>()
        vars.forEach {
            when (redaction.type) {
                RedactionType.VALUE_REGEX -> {
                    if (it.value is String) {
                        val value = it.value as String
                        val redactedValue = Pattern.compile(redaction.lookup).matcher(value)
                            .replaceAll(redaction.replacement)
                        if (value != redactedValue) {
                            redactedVars.add(it.copy(value = redactedValue))
                        } else {
                            redactedVars.add(it)
                        }
                    } else {
                        redactedVars.add(it)
                    }
                }

                RedactionType.IDENTIFIER_MATCH -> {
                    if (it.name == redaction.lookup) {
                        redactedVars.add(it.copy(value = redaction.replacement))
                    } else {
                        redactedVars.add(it)
                    }
                }
            }
        }
        return redactedVars
    }

    override fun create(p0: ModuleManager, p1: AnalyzerModuleConfig) = LiveBreakpointAnalyzer(traceQueryService)
}
