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

import com.google.protobuf.Message
import io.vertx.core.json.JsonObject
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.apache.skywalking.apm.network.logging.v3.LogData
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListener
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListenerFactory
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.protocol.artifact.log.Log
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder

class LiveLogsView(private val subscriptionCache: MetricTypeSubscriptionCache) : LogAnalysisListenerFactory {

    companion object {
        private val log = LoggerFactory.getLogger(LiveLogsView::class.java)

        private val formatter = DateTimeFormatterBuilder()
            .appendPattern("yyyyMMddHHmm")
            .toFormatter()
            .withZone(ZoneOffset.UTC)
    }

    private val sppLogAnalyzer = object : LogAnalysisListener {
        override fun build() = Unit

        override fun parse(logData: LogData.Builder, p1: Message?): LogAnalysisListener {
            if (log.isTraceEnabled) log.trace("Parsing log data {}", logData)

            val subbedArtifacts = subscriptionCache["endpoint_logs"]
            if (subbedArtifacts != null) {
                val logPattern = logData.body.text.text
                val subs = subbedArtifacts[logPattern]
                subs?.forEach { sub ->
                    val log = Log(
                        Instant.ofEpochMilli(logData.timestamp).toKotlinInstant(),
                        logPattern,
                        logData.tags.dataList.find { it.key == "level" }!!.value,
                        logData.tags.dataList.find { it.key == "logger" }?.value,
                        logData.tags.dataList.find { it.key == "thread" }?.value,
                        null,
                        logData.tags.dataList.filter { it.key.startsWith("argument.") }.map { it.value }
                    )
                    val event = JsonObject()
                        .put("type", "LOGS")
                        .put("multiMetrics", false)
                        .put("artifactQualifiedName", JsonObject.mapFrom(sub.subscription.artifactQualifiedName))
                        .put("entityId", logPattern)
                        .put("timeBucket", formatter.format(log.timestamp.toJavaInstant()))
                        .put("log", JsonObject.mapFrom(log))
                    ClusterConnection.getVertx().eventBus().send(sub.consumer.address(), event)
                }
            }
            return this
        }
    }

    override fun create() = sppLogAnalyzer
}
