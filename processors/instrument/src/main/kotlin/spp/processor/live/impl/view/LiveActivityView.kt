package spp.processor.live.impl.view

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.exporter.ExportEvent
import org.apache.skywalking.oap.server.core.exporter.MetricValuesExportService
import org.slf4j.LoggerFactory
import spp.processor.InstrumentProcessor
import spp.processor.live.impl.view.util.EntityNaming
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache

class LiveActivityView(private val subscriptionCache: MetricTypeSubscriptionCache) : MetricValuesExportService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveActivityView::class.java)
    }

    override fun export(event: ExportEvent) {
        if (event.metrics !is WithMetadata) return
        val metadata = (event.metrics as WithMetadata).meta
        val entityName = EntityNaming.getEntityName(metadata)
        if (entityName.isNullOrEmpty()) return
        if (event.type != ExportEvent.EventType.TOTAL) return
        if (log.isTraceEnabled) log.trace("Processing exported event: {}", event)

        val metricName = metadata.metricsName
        val subbedArtifacts = subscriptionCache[metricName]
        if (subbedArtifacts != null) {
            val subs = subbedArtifacts[entityName] ?: return
            val jsonEvent = JsonObject.mapFrom(event)
            subs.forEach { sub ->
                var hasAllEvents = false
                if (sub.subscription.liveViewConfig.viewMetrics.size > 1) {
                    if (sub.waitingEvents.isEmpty()) {
                        sub.waitingEvents.add(event)
                    } else if (sub.subscription.liveViewConfig.viewMetrics.size != sub.waitingEvents.size) {
                        if (sub.waitingEvents.first().metrics.timeBucket == event.metrics.timeBucket) {
                            sub.waitingEvents.removeIf { it.metrics::class.java == event.metrics::class.java }
                            sub.waitingEvents.add(event)
                            if (sub.subscription.liveViewConfig.viewMetrics.size == sub.waitingEvents.size) {
                                hasAllEvents = true
                            }
                        } else if (event.metrics.timeBucket > sub.waitingEvents.first().metrics.timeBucket) {
                            //on new time before received all events for current time
                            sub.waitingEvents.clear()
                            sub.waitingEvents.add(event)
                        }
                    }
                } else {
                    hasAllEvents = true
                }

                if (hasAllEvents && System.currentTimeMillis() - sub.lastUpdated >= sub.subscription.liveViewConfig.refreshRateLimit) {
                    sub.lastUpdated = System.currentTimeMillis()

                    if (sub.waitingEvents.isNotEmpty()) {
                        val multiMetrics = JsonArray()
                        sub.waitingEvents.forEach {
                            multiMetrics.add(
                                JsonObject.mapFrom(it).getJsonObject("metrics")
                                    .put("artifactQualifiedName", sub.subscription.artifactQualifiedName)
                                    .put("entityId", EntityNaming.getEntityName((it.metrics as WithMetadata).meta))
                            )
                        }
                        sub.waitingEvents.clear()

                        InstrumentProcessor.vertx.eventBus().send(
                            sub.consumer.address(),
                            JsonObject().put("metrics", multiMetrics).put("multiMetrics", true)
                        )
                    } else {
                        InstrumentProcessor.vertx.eventBus().send(
                            sub.consumer.address(),
                            jsonEvent.getJsonObject("metrics").put("multiMetrics", false)
                        )
                    }
                }
            }
        }
    }
}
