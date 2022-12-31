/*
 * Source++, the continuous feedback platform for developers.
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
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.PercentMetrics
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.joor.Reflect
import spp.platform.common.ClusterConnection
import spp.platform.common.util.args
import spp.processor.ViewProcessor.realtimeMetricCache
import spp.processor.live.impl.view.util.*
import java.util.concurrent.CopyOnWriteArrayList

class LiveMeterView(private val subscriptionCache: MetricTypeSubscriptionCache) {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val internalSubscribers: MutableList<InternalViewSubscriber> = CopyOnWriteArrayList()
    private val realtimeSubscribers: MutableList<InternalRealtimeViewSubscriber> = CopyOnWriteArrayList()

    fun subscribe(subscriber: InternalViewSubscriber) {
        internalSubscribers.add(subscriber)
    }

    fun unsubscribe(subscriber: InternalViewSubscriber) {
        internalSubscribers.remove(subscriber)
    }

    fun subscribe(subscriber: InternalRealtimeViewSubscriber) {
        realtimeSubscribers.add(subscriber)
    }

    fun unsubscribe(subscriber: InternalRealtimeViewSubscriber) {
        realtimeSubscribers.remove(subscriber)
    }

    suspend fun export(metrics: Metrics, realTime: Boolean) {
        val metadata = (metrics as WithMetadata).meta
        val entityName = EntityNaming.getEntityName(metadata)
        if (entityName.isNullOrEmpty()) return
        internalSubscribers.forEach { it.export(metrics, realTime) }

        var metricName = metadata.metricsName
        if (metricName.startsWith("spp_")) {
            log.debug { "Processing Source++ metrics: {} - Data: {}".args(metricName, metrics) }
        }

        if (realTime && !metricName.startsWith("spp_")) {
            metricName = "${metricName}_realtime"
        } else if (metricName.startsWith("spp_") && !realTime) {
            return // ignore, spp_ metrics are exported only in realtime mode
        }
        val subbedArtifacts = subscriptionCache[metricName]
        if (subbedArtifacts != null) {
            var subs = subbedArtifacts[entityName].orEmpty() +
                    subbedArtifacts[metadata.id].orEmpty() + subbedArtifacts[metricName].orEmpty()

            //remove subscribers with additional filters
            subs = subs.filter {
                val service = it.subscription.artifactLocation?.service
                if (service != null && service != EntityNaming.getServiceId(metrics)) {
                    return@filter false
                }
                return@filter true
            }.toSet()

            if (subs.isNotEmpty()) {
                log.trace { "Exporting event $metricName to {} subscribers".args(subs.size) }
                handleEvent(subs, metrics, realTime)
            }
        }

        if (realtimeSubscribers.isNotEmpty()) {
            val metricId = Reflect.on(metrics).call("id0").get<String>()
            val fullMetricId = metrics.javaClass.simpleName + "_" + metricId

            val jsonMetric = JsonObject.mapFrom(metrics)
            jsonMetric.put("realtime", realTime)
            jsonMetric.put("metric_type", metrics.javaClass.simpleName)
            jsonMetric.put("full_metric_id", fullMetricId)
            if (realTime) {
                val metricsName = jsonMetric.getJsonObject("meta").getString("metricsName")
                if (!metricsName.startsWith("spp_")) {
                    jsonMetric.getJsonObject("meta").put("metricsName", "${metricsName}_realtime")
                }
                setRealtimeValue(jsonMetric, metrics)
                realtimeSubscribers.forEach { it.export(jsonMetric) }
            }
        }
    }

    private suspend fun handleEvent(subs: Set<ViewSubscriber>, metrics: Metrics, realTime: Boolean) {
        val metricId = Reflect.on(metrics).call("id0").get<String>()
        val fullMetricId = metrics.javaClass.simpleName + "_" + metricId

        val jsonMetric = JsonObject.mapFrom(metrics)
        jsonMetric.put("realtime", realTime)
        jsonMetric.put("metric_type", metrics.javaClass.simpleName)
        jsonMetric.put("full_metric_id", fullMetricId)
        if (realTime) {
            val metricsName = jsonMetric.getJsonObject("meta").getString("metricsName")
            if (!metricsName.startsWith("spp_")) {
                jsonMetric.getJsonObject("meta").put("metricsName", "${metricsName}_realtime")
            }
            setRealtimeValue(jsonMetric, metrics)
        }

        subs.forEach { handleSubscriberEvent(it, metrics, jsonMetric) }
    }

    private fun handleSubscriberEvent(sub: ViewSubscriber, metrics: Metrics, jsonMetric: JsonObject) {
        var hasAllEvents = false
        var waitingEventsForBucket = sub.waitingEvents[metrics.timeBucket]
        if (waitingEventsForBucket == null) {
            waitingEventsForBucket = CopyOnWriteArrayList()
            sub.waitingEvents[metrics.timeBucket] = waitingEventsForBucket
        }

        if (sub.subscription.viewConfig.viewMetrics.size > 1) {
            if (waitingEventsForBucket.isEmpty()) {
                waitingEventsForBucket.add(jsonMetric)
            } else {
                waitingEventsForBucket.removeIf { it.getString("metric_type") == metrics::class.simpleName }
                waitingEventsForBucket.add(jsonMetric)
                if (sub.subscription.viewConfig.viewMetrics.size == waitingEventsForBucket.size) {
                    hasAllEvents = true
                }
                //todo: network errors/etc might make it so waitingEventsForBucket never completes
                // remove on timeout (maybe still send with partial status)
            }
        } else {
            hasAllEvents = true
        }

        if (hasAllEvents && System.currentTimeMillis() - sub.lastUpdated >= sub.subscription.viewConfig.refreshRateLimit) {
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
                    log.trace { "Sending multi-metrics $metricsOb to ${sub.subscriberId}" }

                    multiMetrics.add(metricsOb)
                }

                //ensure metrics sorted by subscription order
                val sortedMetrics = JsonArray()
                sub.subscription.viewConfig.viewMetrics.forEach { metricType ->
                    multiMetrics.forEach {
                        val metricData = JsonObject.mapFrom(it)
                        if (metricData.getJsonObject("meta").getString("metricsName") == metricType) {
                            sortedMetrics.add(metricData)
                        }
                    }
                }

                ClusterConnection.getVertx().eventBus().send(
                    sub.consumer.address(),
                    JsonObject().put("metrics", sortedMetrics).put("multiMetrics", true)
                )
            } else {
                log.trace { "Sending metrics $jsonMetric to ${sub.subscriberId}" }
                ClusterConnection.getVertx().eventBus().send(
                    sub.consumer.address(),
                    jsonMetric.put("multiMetrics", false)
                )
            }
        }
    }

    private suspend fun setRealtimeValue(jsonEvent: JsonObject, metrics: Metrics) {
        val rtMetrics = realtimeMetricCache.getIfPresent(jsonEvent.getString("full_metric_id")) ?: return
        val realtimeValue = rtMetrics.calculateAndGetValue()
        if (metrics is PercentMetrics) {
            jsonEvent.put("percentage", realtimeValue)
        }
        jsonEvent.put("value", realtimeValue)
    }
}
