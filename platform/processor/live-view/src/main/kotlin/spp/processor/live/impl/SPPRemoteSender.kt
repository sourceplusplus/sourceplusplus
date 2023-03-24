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
package spp.processor.live.impl

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.remote.RemoteSenderService
import org.apache.skywalking.oap.server.core.remote.data.StreamData
import org.apache.skywalking.oap.server.core.remote.selector.Selector
import org.apache.skywalking.oap.server.core.storage.StorageID
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.joor.Reflect
import spp.platform.common.ClusterConnection
import spp.processor.ViewProcessor
import spp.processor.live.impl.view.model.ClusterMetrics
import spp.processor.live.impl.view.util.EntityNaming
import spp.protocol.artifact.metrics.MetricType

class SPPRemoteSender(
    moduleManager: ModuleManager,
    private val delegate: RemoteSenderService
) : RemoteSenderService(moduleManager) {

    private val supportedRealtimeMetrics = MetricType.ALL.map { it.metricId + "_rec" }

    override fun send(nextWorkName: String, metrics: StreamData, selector: Selector) {
        if (nextWorkName.startsWith("spp_") || supportedRealtimeMetrics.contains(nextWorkName)) {
            val metadata = (metrics as WithMetadata).meta
            val entityName = EntityNaming.getEntityName(metadata)
            if (!entityName.isNullOrEmpty()) {
                val copiedMetrics = metrics::class.java.newInstance() as Metrics
                copiedMetrics.deserialize(metrics.serialize().build())

                GlobalScope.launch(ClusterConnection.getVertx().dispatcher()) {
                    if (copiedMetrics.javaClass.simpleName.startsWith("spp_")) {
                        Reflect.on(copiedMetrics).set("metadata", (metrics as WithMetadata).meta)
                    }

                    val metricId = Reflect.on(copiedMetrics).call("id0").get<StorageID>().build()
                    val fullMetricId = copiedMetrics.javaClass.simpleName + "_" + metricId

                    Vertx.currentContext().putLocal("current_metrics", copiedMetrics)
                    ViewProcessor.realtimeMetricCache.compute(fullMetricId) { _, old ->
                        val new = ClusterMetrics(copiedMetrics)
                        if (old != null) {
                            new.metrics.combine(old.metrics)
                        }
                        new
                    }
                    ViewProcessor.liveViewService.meterView.export(copiedMetrics, true)
                }
            }
        }

        delegate.send(nextWorkName, metrics, selector)
    }
}
