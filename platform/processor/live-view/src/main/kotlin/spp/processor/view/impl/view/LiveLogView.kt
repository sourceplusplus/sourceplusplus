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

import com.google.protobuf.Message
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.skywalking.apm.network.logging.v3.LogData
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListener
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListenerFactory
import org.apache.skywalking.oap.server.core.analysis.Layer
import spp.platform.common.ClusterConnection
import spp.processor.view.ViewProcessor
import spp.processor.view.impl.view.util.MetricTypeSubscriptionCache
import spp.processor.view.model.LiveGaugeValueMetrics
import spp.processor.view.model.ViewSubscriber
import spp.protocol.artifact.log.Log
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder

class LiveLogView(private val subscriptionCache: MetricTypeSubscriptionCache) : LogAnalysisListenerFactory {

    companion object {
        private val log = KotlinLogging.logger {}

        private val formatter = DateTimeFormatterBuilder()
            .appendPattern("yyyyMMddHHmm")
            .toFormatter()
            .withZone(ZoneOffset.UTC)
    }

    private val sppLogAnalyzer = object : LogAnalysisListener {
        override fun build() = Unit

        override fun parse(logData: LogData.Builder, p1: Message?): LogAnalysisListener {
            var meterId: String? = null
            var metricId: String? = null
            logData.tags.dataList.forEach {
                when (it.key) {
                    "meter_id" -> meterId = it.value
                    "metric_id" -> metricId = it.value
                }
            }
            if (meterId != null && metricId != null) {
                //live meter sent through live log
                val metricValue = logData.body.text.text
                val timeBucket = formatter.format(Instant.ofEpochMilli(logData.timestamp)).toLong()
                val copiedMetrics = LiveGaugeValueMetrics(metricId!!, timeBucket, metricValue)
                GlobalScope.launch(ClusterConnection.getVertx().dispatcher()) {
                    ViewProcessor.liveViewService.meterView.export(copiedMetrics, true)
                }
                return this
            }

            val subbedArtifacts = subscriptionCache["endpoint_logs"].orEmpty() +
                    subscriptionCache["service_logs"].orEmpty()
            if (subbedArtifacts.isNotEmpty()) {
                val logPattern = logData.body.text.text
                var subs = (subbedArtifacts[logPattern].orEmpty() + subbedArtifacts["*"].orEmpty()).toMutableSet()

                val service = Service.fromName(logData.service)
                subs += subbedArtifacts[service.name].orEmpty()
                subs += subbedArtifacts[service.id].orEmpty()
                subs += subbedArtifacts[Service.fromName(service.name).id].orEmpty()

                //remove subscribers with additional filters
                subs = subs.filter {
//                    if (it.subscription.serviceInstance?.let {
//                            it != logData.serviceInstance
//                        } == true) return@filter false
                    if (it.subscription.location?.service?.let {
                            !it.isSameLocation(it.withName(logData.service))
                        } == true) return@filter false

                    //get log location (if available)
                    val logSource = logData.tags.dataList.find { it.key == "source" }?.value
                    val logLineNumber = logData.tags.dataList.find { it.key == "line" }?.value?.toInt()
                    val logLocation = if (logSource != null) {
                        LiveSourceLocation(
                            source = logSource,
                            line = logLineNumber ?: -1,
                            service = Service.fromName(logData.service),
                            //serviceInstance = logData.serviceInstance
                        )
                    } else null
                    if (it.subscription.location?.let {
                            if (logLocation == null) true else !it.isSameLocation(logLocation)
                        } == true) return@filter false
                    return@filter true
                }.toMutableSet()

                subs.forEach { sentToSub(it, logData) }
            }

            return this
        }
    }

    private fun sentToSub(sub: ViewSubscriber, logData: LogData.Builder) {
        val logPattern = logData.body.text.text
        log.debug { "Sending log pattern $logPattern to subscriber $sub" }

        //get log location (if available)
        val logSource = logData.tags.dataList.find { it.key == "source" }?.value
        val logLineNumber = logData.tags.dataList.find { it.key == "line" }?.value?.toInt()
        val logLocation = if (logSource != null) {
            LiveSourceLocation(
                logSource,
                logLineNumber ?: -1,
                service = Service.fromName(logData.service),
                //serviceInstance = logData.serviceInstance
            )
        } else null

        //publish log record
        val logRecord = Log(
            Instant.ofEpochMilli(logData.timestamp),
            logPattern,
            logData.tags.dataList.find { it.key == "level" }!!.value,
            logData.tags.dataList.find { it.key == "logger" }?.value,
            logData.tags.dataList.find { it.key == "thread" }?.value,
            null,
            logData.tags.dataList.filter { it.key.startsWith("argument.") }.map { it.value },
            logLocation
        )
        val event = JsonObject()
            .put("type", "LOGS")
            .put("multiMetrics", false)
            .put("entityId", logPattern)
            .put("timeBucket", formatter.format(logRecord.timestamp))
            .put("log", JsonObject.mapFrom(logRecord))
        ClusterConnection.getVertx().eventBus().send(sub.consumer.address(), event)
    }

    override fun create(layer: Layer?) = sppLogAnalyzer
}
