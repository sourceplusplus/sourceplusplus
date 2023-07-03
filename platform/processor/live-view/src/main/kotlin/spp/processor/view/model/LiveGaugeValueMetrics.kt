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
package spp.processor.view.model

import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.MetricsMetaInfo
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.remote.grpc.proto.RemoteData
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine
import org.apache.skywalking.oap.server.core.storage.StorageID

class LiveGaugeValueMetrics(
    private var entityId: String,
    timeBucket: Long,
    val value: Any
) : Metrics(), WithMetadata {

    init {
        setTimeBucket(timeBucket)
    }

    override fun id0(): StorageID {
        return StorageID()
            .append(TIME_BUCKET, timeBucket)
            .append(ENTITY_ID, entityId)
    }

    override fun getMeta(): MetricsMetaInfo {
        return MetricsMetaInfo(entityId, DefaultScopeDefine.ALL, entityId)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false
        other as LiveGaugeValueMetrics
        if (entityId != other.entityId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + entityId.hashCode()
        return result
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

    override fun serialize(): RemoteData.Builder {
        throw UnsupportedOperationException("serialize() should not be called in this class")
    }

    override fun deserialize(var1: RemoteData) {
        throw UnsupportedOperationException("deserialize() should not be called in this class")
    }

    override fun toHour(): Metrics {
        throw UnsupportedOperationException("toHour() should not be called in this class")
    }

    override fun toDay(): Metrics {
        throw UnsupportedOperationException("toDay() should not be called in this class")
    }
}
