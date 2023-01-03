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

import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.MetricsMetaInfo
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.remote.grpc.proto.RemoteData
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine

class LiveGaugeValueMetrics(
    private var entityId: String,
    timeBucket: Long,
    val value: Any
) : Metrics(), WithMetadata {

    init {
        setTimeBucket(timeBucket)
    }

    override fun id0(): String {
        val var1 = StringBuilder(timeBucket.toString())
        var1.append("_").append(entityId)
        return var1.toString()
    }

    override fun hashCode(): Int {
        var var1 = 17
        var1 = 31 * var1 + entityId.hashCode()
        var1 = 31 * var1 + timeBucket.toInt()
        return var1
    }

    override fun remoteHashCode(): Int {
        throw UnsupportedOperationException("remoteHashCode() should not be called in this class")
    }

    override fun combine(metrics: Metrics?): Boolean {
        throw UnsupportedOperationException("combine() should not be called in this class")
    }

    override fun calculate() {
        throw UnsupportedOperationException("calculate() should not be called in this class")
    }

    override fun equals(var1: Any?): Boolean {
        return if (this === var1) {
            true
        } else if (var1 == null) {
            false
        } else if (this.javaClass != var1.javaClass) {
            false
        } else {
            val var2 = var1 as LiveGaugeValueMetrics
            if (entityId != var2.entityId) {
                false
            } else {
                timeBucket == var2.timeBucket
            }
        }
    }

    override fun serialize(): RemoteData.Builder {
        throw UnsupportedOperationException("serialize() should not be called in this class")
    }

    override fun deserialize(var1: RemoteData) {
        throw UnsupportedOperationException("deserialize() should not be called in this class")
    }

    override fun getMeta(): MetricsMetaInfo {
        return MetricsMetaInfo(entityId, DefaultScopeDefine.ALL, entityId)
    }

    override fun toHour(): Metrics {
        throw UnsupportedOperationException("toHour() should not be called in this class")
    }

    override fun toDay(): Metrics {
        throw UnsupportedOperationException("toDay() should not be called in this class")
    }
}
