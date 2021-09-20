package spp.processor.live.impl.view

import com.sourceplusplus.protocol.artifact.log.Log
import io.vertx.core.json.JsonObject
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.apache.skywalking.apm.network.logging.v3.LogData
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListener
import org.apache.skywalking.oap.log.analyzer.provider.log.listener.LogAnalysisListenerFactory
import org.slf4j.LoggerFactory
import spp.processor.SourceProcessor
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
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

        override fun parse(logData: LogData.Builder): LogAnalysisListener {
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
                        .put("artifactQualifiedName", sub.subscription.artifactQualifiedName)
                        .put("entityId", logPattern)
                        .put("timeBucket", formatter.format(log.timestamp.toJavaInstant()))
                        .put("log", JsonObject.mapFrom(log).apply { remove("formattedMessage") })
                    SourceProcessor.vertx.eventBus().send(sub.consumer.address(), event)
                }
            }
            return this
        }
    }

    override fun create() = sppLogAnalyzer
}
