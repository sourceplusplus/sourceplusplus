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

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.protobuf.Message
import io.vertx.core.json.JsonObject
import org.apache.skywalking.apm.network.logging.v3.LogData
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListener
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListenerFactory
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.processor.InstrumentProcessor.liveInstrumentService
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.artifact.log.Log
import spp.protocol.artifact.log.LogOrderType
import spp.protocol.artifact.log.LogResult
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveLogHit
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.set

class LiveLogAnalyzer : LogAnalysisListener, LogAnalysisListenerFactory {

    companion object {
        private val log = LoggerFactory.getLogger(LiveBreakpointAnalyzer::class.java)
    }

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

        handleLogHit(
            LiveLogHit(
                logId!!,
                Instant.ofEpochMilli(logData.timestamp),
                logData.serviceInstance,
                logData.service,
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
                            arguments = arguments
                        )
                    ),
                    total = -1
                )
            )
        )
        logPublishCache.put(logId!!, System.currentTimeMillis())
        return this
    }

    private fun handleLogHit(hit: LiveLogHit) {
        if (log.isTraceEnabled) log.trace("Live log hit: {}", hit)
        val liveInstrument = liveInstrumentService._getDeveloperInstrumentById(hit.logId)
        if (liveInstrument != null) {
            val instrumentMeta = liveInstrument.instrument.meta as MutableMap<String, Any>
            if ((instrumentMeta["hit_count"] as AtomicInteger?)?.incrementAndGet() == 1) {
                instrumentMeta["first_hit_at"] = System.currentTimeMillis().toString()
            }
            instrumentMeta["last_hit_at"] = System.currentTimeMillis().toString()
        }

        val devInstrument = liveInstrument ?: liveInstrumentService.getCachedDeveloperInstrument(hit.logId)
        ClusterConnection.getVertx().eventBus().publish(
            toLiveInstrumentSubscriberAddress(devInstrument.developerAuth.selfId),
            JsonObject.mapFrom(
                LiveInstrumentEvent(LiveInstrumentEventType.LOG_HIT, JsonObject.mapFrom(hit).toString())
            )
        )
        if (log.isTraceEnabled) log.trace("Published live log hit")
    }

    override fun create() = LiveLogAnalyzer()
}
