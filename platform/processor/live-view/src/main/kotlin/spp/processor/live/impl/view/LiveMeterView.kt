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

import mu.KotlinLogging
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import spp.platform.common.util.Msg
import spp.processor.live.impl.view.util.EntityNaming
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache

class LiveMeterView(private val subscriptionCache: MetricTypeSubscriptionCache) : AbstractLiveView() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    fun export(metrics: Metrics, realTime: Boolean) {
        if (metrics !is WithMetadata) return
        val metadata = (metrics as WithMetadata).meta
        val entityName = EntityNaming.getEntityName(metadata)
        if (entityName.isNullOrEmpty()) return
        if (log.isTraceEnabled) log.trace("Processing exported metrics: {}", metrics)

        var metricName = metadata.metricsName
        if (realTime) {
            metricName = "${metricName}_realtime"
        }
        val subbedArtifacts = subscriptionCache[metricName]
        if (subbedArtifacts != null) {
            val subs = subbedArtifacts[entityName].orEmpty() +
                    subbedArtifacts[metadata.id].orEmpty() + subbedArtifacts[metricName].orEmpty()
            if (subs.isNotEmpty()) {
                log.trace { Msg.msg("Exporting event $metricName to {} subscribers", subs.size) }
                handleEvent(subs, metrics, realTime)
            }
        }
    }
}
