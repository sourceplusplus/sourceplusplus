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
package spp.processor.view.impl.view

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.apache.skywalking.oap.server.core.analysis.IDManager
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.PercentMetrics
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.storage.StorageID
import org.joor.Reflect
import spp.platform.common.ClusterConnection
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.view.ViewProcessor
import spp.processor.view.ViewProcessor.realtimeMetricCache
import spp.processor.view.impl.view.util.*
import spp.processor.view.model.LiveMetricConvert
import spp.protocol.instrument.event.LiveMeterHit
import java.time.Instant
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
        if (realTime && !metricName.startsWith("spp_")) {
            metricName = "${metricName}_realtime"
        } else if (metricName.startsWith("spp_") && !realTime) {
            return // ignore, spp_ metrics are exported only in realtime mode
        }

        val jsonEvent = toViewEventJson(metrics, realTime)
        val metricService = EntityNaming.getServiceId(metrics)
        val metricServiceName = metricService?.let { IDManager.ServiceID.analysisId(it).name }
        val metricServiceInstance = EntityNaming.getServiceInstanceId(metadata)
        if (metricName.startsWith("spp_")) {
            log.debug { "Processing Source++ metrics: {} - Data: {}".args(metricName, jsonEvent) }
            val meterId = ViewProcessor.liveViewService.meterProcessService.converts()
                .filterIsInstance<LiveMetricConvert>().firstNotNullOfOrNull {
                    findMeterId(metricName, it)
                } ?: metricName
            val liveMeter = SourceStorage.getLiveInstrument(meterId, true)
            if (liveMeter != null) {
                SourceStorage.addLiveInstrumentEvent(
                    liveMeter,
                    LiveMeterHit(
                        liveMeter, jsonEvent, Instant.now(),
                        metricServiceInstance ?: "Unknown", //todo: test; always unknown for spp_ metrics?
                        metricService ?: "Unknown"
                    )
                )
            } else {
                log.error { "LiveMeter not found for metric: $metricName" }
            }
        }

        val subbedArtifacts = subscriptionCache[metricName]
        if (subbedArtifacts != null) {
            var subs = subbedArtifacts[entityName].orEmpty() +
                    subbedArtifacts[metadata.id].orEmpty() + subbedArtifacts[metricName].orEmpty()

            //remove subscribers with additional filters
            subs = subs.filter {
                val subLocation = it.subscription.artifactLocation
                if (subLocation != null) {
                    val metricsDataLocation = subLocation.copy(
                        service = subLocation.service?.let { metricServiceName?.substringBefore("|") },
                        serviceInstance = subLocation.serviceInstance?.let { metricServiceInstance },
                        commitId = subLocation.commitId?.let { metricServiceName?.substringAfter("|") }
                    )
                    return@filter subLocation.isSameLocation(metricsDataLocation)
                }
                return@filter true
            }.toSet()

            if (subs.isNotEmpty()) {
                log.trace { "Exporting event $metricName to {} subscribers".args(subs.size) }
                subs.forEach { handleSubscriberEvent(it, metrics, jsonEvent) }
            } else {
                log.trace { "No subscribers for event $metricName" }
            }
        }

        if (realTime && realtimeSubscribers.isNotEmpty()) {
            realtimeSubscribers.forEach { it.export(jsonEvent) }
        }
    }

    private fun findMeterId(metricName: String, rule: LiveMetricConvert): String? {
        var checkName = metricName
        do {
            if (rule.config.getLiveMetricsRules().any { it.meterIds.contains(checkName) }) {
                return checkName
            }
            checkName = checkName.substringBeforeLast("_")
        } while (checkName.contains("_"))
        return null
    }

    private suspend fun toViewEventJson(metrics: Metrics, realTime: Boolean): JsonObject {
        val metricId = Reflect.on(metrics).call("id0").get<StorageID>().build()
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
            jsonMetric.put("currentTime", System.currentTimeMillis())
        }

        return jsonMetric
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

        val timeSinceRefresh = System.currentTimeMillis() - sub.lastUpdated
        if (hasAllEvents && timeSinceRefresh >= sub.subscription.viewConfig.refreshRateLimit) {
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
