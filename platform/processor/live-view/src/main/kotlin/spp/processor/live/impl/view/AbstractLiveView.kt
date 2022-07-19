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
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.exporter.ExportEvent
import spp.platform.common.FeedbackProcessor
import spp.processor.live.impl.view.util.EntityNaming
import spp.processor.live.impl.view.util.ViewSubscriber

abstract class AbstractLiveView {

    //todo: only ActivityView uses, should probably be moved there
    @Synchronized
    fun handleEvent(subs: Set<ViewSubscriber>, event: ExportEvent) {
        val jsonEvent = JsonObject.mapFrom(event)
        subs.forEach { sub ->
            var hasAllEvents = false
            var waitingEventsForBucket = sub.waitingEvents[event.metrics.timeBucket]
            if (waitingEventsForBucket == null) {
                waitingEventsForBucket = mutableListOf()
                sub.waitingEvents[event.metrics.timeBucket] = waitingEventsForBucket
            }

            if (sub.subscription.liveViewConfig.viewMetrics.size > 1) {
                if (waitingEventsForBucket.isEmpty()) {
                    waitingEventsForBucket.add(event)
                } else {
                    waitingEventsForBucket.removeIf { it.metrics::class.java == event.metrics::class.java }
                    waitingEventsForBucket.add(event)
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
                        multiMetrics.add(
                            JsonObject.mapFrom(it).getJsonObject("metrics")
                                .put("artifactQualifiedName", JsonObject.mapFrom(sub.subscription.artifactQualifiedName))
                                .put("entityName", EntityNaming.getEntityName((it.metrics as WithMetadata).meta))
                        )
                    }

                    FeedbackProcessor.vertx.eventBus().send(
                        sub.consumer.address(),
                        JsonObject().put("metrics", multiMetrics).put("multiMetrics", true)
                    )
                } else {
                    FeedbackProcessor.vertx.eventBus().send(
                        sub.consumer.address(),
                        jsonEvent.getJsonObject("metrics").put("multiMetrics", false)
                    )
                }
            }
        }
    }
}
