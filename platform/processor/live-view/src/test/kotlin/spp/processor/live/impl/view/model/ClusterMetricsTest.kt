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
package spp.processor.live.impl.view.model

import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue
import org.apache.skywalking.oap.server.core.analysis.meter.function.avg.AvgLabeledFunction
import org.apache.skywalking.oap.server.core.analysis.metrics.DataTable
import org.apache.skywalking.oap.server.core.storage.StorageData
import org.apache.skywalking.oap.server.core.storage.type.StorageBuilder
import org.joor.Reflect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClusterMetricsTest {

    @Test
    fun `metrics with data table`() {
        val metrics = object : AvgLabeledFunction() {
            override fun createNew(): AcceptableValue<DataTable> {
                return object : AcceptableValue<DataTable> {
                    override fun accept(entity: MeterEntity?, value: DataTable?) = throw UnsupportedOperationException()
                    override fun createNew(): AcceptableValue<DataTable> = throw UnsupportedOperationException()
                    override fun builder(): Class<StorageBuilder<StorageData>> = throw UnsupportedOperationException()
                    override fun setTimeBucket(timeBucket: Long): Unit = throw UnsupportedOperationException()
                    override fun getTimeBucket(): Long = throw UnsupportedOperationException()
                }
            }
        }
        val metricsDataTable = Reflect.on(metrics).field("value").get<DataTable>()
        metricsDataTable.put("key", 100)

        val clusterMetrics = ClusterMetrics(metrics)
        val value = clusterMetrics.calculateAndGetValue()
        assertTrue(value is Map<*, *>)
        (value as Map<*, *>).let {
            assertEquals(1, it.size)
            assertEquals(100L, it["key"])
        }
    }
}
