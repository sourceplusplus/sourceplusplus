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

import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.exporter.ExportEvent
import org.apache.skywalking.oap.server.core.exporter.MetricValuesExportService
import org.slf4j.LoggerFactory
import spp.processor.live.impl.view.util.EntityNaming
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache

class LiveActivityView(private val subscriptionCache: MetricTypeSubscriptionCache) :
    AbstractLiveView(), MetricValuesExportService {

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
            val subs = subbedArtifacts[entityName].orEmpty() + subbedArtifacts[metadata.id].orEmpty()
            if (subs.isNotEmpty()) {
                log.debug("Exporting event $metricName to {} subscribers", subs.size)
                handleEvent(subs, event)
            }
        }
    }
}
