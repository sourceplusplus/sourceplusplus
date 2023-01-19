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

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
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
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.protocol.artifact.log.Log
import spp.protocol.artifact.log.LogOrderType
import spp.protocol.artifact.log.LogResult
import spp.protocol.instrument.event.LiveLogHit
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscription
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.collections.set

class LiveLogAnalyzer : LogAnalysisListener, LogAnalysisListenerFactory {

    private val log = KotlinLogging.logger {}
    private var logPublishRateLimit = 1000
    private val logPublishCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build(object : CacheLoader<String, Long>() {
            override fun load(key: String): Long = -1
        })

//        init {
//            //todo: map of rate limit per log id
//            ClusterConnection.getVertx().eventBus().consumer<Int>(ProcessorAddress.SET_LOG_PUBLISH_RATE_LIMIT) {
//                logPublishRateLimit = it.body()
//            }
//        }

    override fun build() = Unit

    override fun parse(logData: LogData.Builder, p1: Message?): LogAnalysisListener {
        var logId: String? = null
        var logger: String? = null
        var thread: String? = null
        val arguments = mutableListOf<String>()
        logData.tags.dataList.forEach {
            when {
                "log_id" == it.key -> logId = it.value
                "logger" == it.key -> logger = it.value
                "thread" == it.key -> thread = it.value
                it.key.startsWith("argument.") -> arguments.add(it.value)
            }
        }
        if (logId == null) return this

        val logLastPublished = logPublishCache.get(logId!!)
        if (System.currentTimeMillis() - logLastPublished < logPublishRateLimit) {
            return this
        }
        logPublishCache.put(logId!!, System.currentTimeMillis())

        //get log location (if available)
        val logSource = logData.tags.dataList.find { it.key == "source" }?.value
        val logLineNumber = logData.tags.dataList.find { it.key == "line" }?.value?.toInt()
        val logLocation = if (logSource != null) {
            LiveSourceLocation(
                logSource,
                logLineNumber ?: -1,
                service = logData.service,
                serviceInstance = logData.serviceInstance
            )
        } else null

        GlobalScope.launch(ClusterConnection.getVertx().dispatcher()) {
            handleLogHit(
                IntermediateLiveLogHit(
                    logId!!,
                    LogResult(
                        orderType = LogOrderType.NEWEST_LOGS,
                        timestamp = Instant.ofEpochMilli(logData.timestamp),
                        logs = listOf(
                            Log(
                                timestamp = Instant.ofEpochMilli(logData.timestamp),
                                content = logData.body.text.text,
                                level = "Live",
                                logger = logger,
                                thread = thread,
                                arguments = arguments,
                                location = logLocation
                            )
                        ),
                        total = -1
                    ),
                    Instant.ofEpochMilli(logData.timestamp),
                    logData.serviceInstance,
                    logData.service
                )
            )
        }
        return this
    }

    private suspend fun handleLogHit(intermediateHit: IntermediateLiveLogHit) {
        log.trace { "Live log hit: {}".args(intermediateHit) }
        val liveInstrument = SourceStorage.getLiveInstrument(intermediateHit.logId, true)
        if (liveInstrument != null) {
            val instrumentMeta = liveInstrument.meta as MutableMap<String, Any>
            instrumentMeta["hit_count"] = (instrumentMeta["hit_count"] as Int?)?.plus(1) ?: 1
            if (instrumentMeta["hit_count"] == 1) {
                instrumentMeta["first_hit_at"] = System.currentTimeMillis().toString()
            }
            instrumentMeta["last_hit_at"] = System.currentTimeMillis().toString()
            SourceStorage.updateLiveInstrument(liveInstrument.id!!, liveInstrument)

            val developerId = liveInstrument.meta["spp.developer_id"] as String

            val hit = LiveLogHit(
                liveInstrument,
                intermediateHit.logResult,
                intermediateHit.occurredAt,
                intermediateHit.serviceInstance,
                intermediateHit.service
            )
            ClusterConnection.getVertx().eventBus().publish(
                toLiveInstrumentSubscription(hit.instrument.id!!),
                JsonObject.mapFrom(hit)
            )
            //todo: remove dev-specific publish
            ClusterConnection.getVertx().eventBus().publish(
                toLiveInstrumentSubscriberAddress(developerId),
                JsonObject.mapFrom(hit)
            )
            log.trace { "Published live log hit" }
        } else {
            log.warn { "Live log hit for unknown log id: {}".args(intermediateHit.logId) }
        }
    }

    override fun create(layer: Layer?) = LiveLogAnalyzer()

    data class IntermediateLiveLogHit(
        val logId: String,
        val logResult: LogResult,
        val occurredAt: Instant,
        val serviceInstance: String,
        val service: String
    )
}
