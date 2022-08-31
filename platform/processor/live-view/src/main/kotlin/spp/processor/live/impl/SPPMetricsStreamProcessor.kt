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
package spp.processor.live.impl

import org.apache.skywalking.oap.server.core.analysis.Stream
import org.apache.skywalking.oap.server.core.analysis.StreamDefinition
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsPersistentWorker
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor
import org.apache.skywalking.oap.server.library.module.ModuleDefineHolder
import spp.processor.ViewProcessor

class SPPMetricsStreamProcessor(private val realProcessor: MetricsStreamProcessor) : MetricsStreamProcessor() {

    override fun `in`(metrics: Metrics) {
        realProcessor.`in`(metrics)
        ViewProcessor.liveViewProcessor.meterView.export(metrics, true)
    }

    override fun create(moduleDefineHolder: ModuleDefineHolder?, stream: Stream?, metricsClass: Class<out Metrics>?) {
        realProcessor.create(moduleDefineHolder, stream, metricsClass)
    }

    override fun create(
        moduleDefineHolder: ModuleDefineHolder?,
        stream: StreamDefinition?,
        metricsClass: Class<out Metrics>?
    ) {
        realProcessor.create(moduleDefineHolder, stream, metricsClass)
    }

    override fun getPersistentWorkers(): MutableList<MetricsPersistentWorker> {
        return realProcessor.getPersistentWorkers()
    }

    override fun setL1FlushPeriod(l1FlushPeriod: Long) {
        realProcessor.setL1FlushPeriod(l1FlushPeriod)
    }

    override fun getL1FlushPeriod(): Long {
        return realProcessor.getL1FlushPeriod()
    }

    override fun setEnableDatabaseSession(enableDatabaseSession: Boolean) {
        realProcessor.setEnableDatabaseSession(enableDatabaseSession)
    }

    override fun isEnableDatabaseSession(): Boolean {
        return realProcessor.isEnableDatabaseSession()
    }

    override fun setStorageSessionTimeout(storageSessionTimeout: Long) {
        realProcessor.setStorageSessionTimeout(storageSessionTimeout)
    }

    override fun setMetricsDataTTL(metricsDataTTL: Int) {
        realProcessor.setMetricsDataTTL(metricsDataTTL)
    }
}
