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

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.apache.skywalking.oap.server.core.analysis.metrics.*
import org.joor.Reflect
import spp.platform.common.ClusterConnection
import spp.platform.common.util.Msg
import spp.processor.live.impl.view.util.EntityNaming
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.processor.live.impl.view.util.ViewSubscriber
import java.util.concurrent.ConcurrentHashMap

class LiveMeterView(private val subscriptionCache: MetricTypeSubscriptionCache) {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val rtMetricCache = ConcurrentHashMap<String, Pair<Double, Long>>()

    fun export(metrics: Metrics, realTime: Boolean) {
        if (metrics !is WithMetadata) return
        val metadata = (metrics as WithMetadata).meta
        val entityName = EntityNaming.getEntityName(metadata)
        if (entityName.isNullOrEmpty()) return
        if (log.isTraceEnabled) log.trace("Processing exported metrics: {}", metrics)

        var metricName = metadata.metricsName
        if (realTime && !metricName.startsWith("spp_")) {
            metricName = "${metricName}_realtime"
        } else if (metricName.startsWith("spp_") && !realTime) {
            return // ignore, spp_ metrics are exported only in realtime mode
        }
        val subbedArtifacts = subscriptionCache[metricName]
        if (subbedArtifacts != null) {
            val subs = subbedArtifacts[entityName].orEmpty() +
                    subbedArtifacts[metadata.id].orEmpty() + subbedArtifacts[metricName].orEmpty()
            if (subs.isNotEmpty()) {
                log.trace { Msg.msg("Exporting event $metricName to {} subscribers", subs.size) }
                handleEvent(subs, metrics, realTime)
            }
        }
    }

    @Synchronized
    fun handleEvent(subs: Set<ViewSubscriber>, metrics: Metrics, realTime: Boolean) {
        val metricId by lazy { Reflect.on(metrics).call("id0").get<String>() }
        val fullMetricId = metrics.javaClass.simpleName + "_" + metricId
        if (metrics.javaClass.simpleName.contains("EndpointCpm")) {
            rtMetricCache.compute(fullMetricId) { _: String, v: Pair<Double, Long>? ->
                if (v != null) {
                    Pair(v.first + (metrics as CPMMetrics).total.toDouble(), v.second + 1)
                } else {
                    Pair(1.0, 1)
                }
            }
        }
        if (metrics.javaClass.simpleName.contains("EndpointSla")) {
            rtMetricCache.compute(fullMetricId) { _: String, v: Pair<Double, Long>? ->
                if (v != null) {
                    Pair(v.first + (metrics as PercentMetrics).match, v.second + 1)
                } else {
                    Pair((metrics as PercentMetrics).total.toDouble(), 1)
                }
            }
        }
        if (metrics.javaClass.simpleName.contains("EndpointRespTime")) {
            rtMetricCache.compute(fullMetricId) { _: String, v: Pair<Double, Long>? ->
                if (v != null) {
                    Pair((metrics as LongAvgMetrics).summation.toDouble(), v.second + 1)
                } else {
                    Pair((metrics as LongAvgMetrics).summation.toDouble(), 1)
                }
            }
        }

        val jsonMetric = JsonObject.mapFrom(metrics)
        jsonMetric.put("realtime", realTime)
        jsonMetric.put("metric_type", metrics.javaClass.simpleName)
        jsonMetric.put("full_metric_id", fullMetricId)

        subs.forEach { sub ->
            var hasAllEvents = false
            var waitingEventsForBucket = sub.waitingEvents[metrics.timeBucket]
            if (waitingEventsForBucket == null) {
                waitingEventsForBucket = mutableListOf()
                sub.waitingEvents[metrics.timeBucket] = waitingEventsForBucket
            }

            if (sub.subscription.liveViewConfig.viewMetrics.size > 1) {
                if (waitingEventsForBucket.isEmpty()) {
                    waitingEventsForBucket.add(jsonMetric)
                } else {
                    waitingEventsForBucket.removeIf { it.getString("metric_type") == metrics::class.simpleName }
                    waitingEventsForBucket.add(jsonMetric)
                    if (sub.subscription.liveViewConfig.viewMetrics.size == waitingEventsForBucket.size) {
                        hasAllEvents = true
                    }
                    //todo: network errors/etc might make it so waitingEventsForBucket never completes
                    // remove on timeout (maybe still send with partial status)
                }
            } else {
                hasAllEvents = true
            }

            if (hasAllEvents && System.currentTimeMillis() - sub.lastUpdated >= sub.subscription.liveViewConfig.refreshRateLimit) {
                sub.lastUpdated = System.currentTimeMillis()

                if (waitingEventsForBucket.isNotEmpty()) {
                    val multiMetrics = JsonArray()
                    waitingEventsForBucket.forEach {
                        val metricsOb = JsonObject.mapFrom(it)
                            .put(
                                "artifactQualifiedName",
                                JsonObject.mapFrom(sub.subscription.artifactQualifiedName)
                            )
                            .put("entityName", EntityNaming.getEntityName((metrics as WithMetadata).meta))
                        if (metricsOb.getBoolean("realtime") == true) {
                            setRealtimeValue(metricsOb)
                        }
                        log.trace { "Sending multi-metrics $metricsOb to ${sub.subscriberId}" }

                        multiMetrics.add(metricsOb)
                    }

                    ClusterConnection.getVertx().eventBus().send(
                        sub.consumer.address(),
                        JsonObject().put("metrics", multiMetrics).put("multiMetrics", true)
                    )
                } else {
                    if (jsonMetric.getBoolean("realtime") == true) {
                        setRealtimeValue(jsonMetric)
                    }

                    log.trace { "Sending metrics $jsonMetric to ${sub.subscriberId}" }
                    ClusterConnection.getVertx().eventBus().send(
                        sub.consumer.address(),
                        jsonMetric.put("multiMetrics", false)
                    )
                }
            }
        }
    }

    private fun setRealtimeValue(jsonEvent: JsonObject) {
        val metricType = jsonEvent.getString("metric_type")
        if (metricType.contains("EndpointSla")) {
            val sla = rtMetricCache[jsonEvent.getString("full_metric_id")]!!
            val realtimeValue = (sla.first / sla.second) * 10000
            jsonEvent.put("percentage", realtimeValue)
        } else if (!metricType.startsWith("spp_")) {
            val realtimeValue = rtMetricCache[jsonEvent.getString("full_metric_id")]!!.first
            jsonEvent.put("value", realtimeValue.toLong())
        }
    }
}
