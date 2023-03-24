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
package integration.testData

import org.apache.skywalking.oap.server.core.analysis.metrics.CPMMetrics
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.MetricsMetaInfo
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.remote.grpc.proto.RemoteData
import org.apache.skywalking.oap.server.core.storage.StorageID
import org.apache.skywalking.oap.server.core.storage.annotation.Column

class EndpointCpmMetrics : CPMMetrics(), WithMetadata {

    @Column(name = "entity_id", length = 512)
    var entityId: String? = null

    @Column(name = "service_id", length = 256)
    var serviceId: String? = null

    override fun id0(): StorageID {
        return StorageID()
            .append(TIME_BUCKET, timeBucket)
            .append(ENTITY_ID, entityId)
    }

    override fun serialize(): RemoteData.Builder {
        val var1 = RemoteData.newBuilder()
        var1.addDataStrings(entityId)
        var1.addDataStrings(serviceId)
        var1.addDataLongs(value)
        var1.addDataLongs(total)
        var1.addDataLongs(timeBucket)
        return var1
    }

    override fun deserialize(var1: RemoteData) {
        entityId = var1.getDataStrings(0)
        serviceId = var1.getDataStrings(1)
        value = var1.getDataLongs(0)
        total = var1.getDataLongs(1)
        timeBucket = var1.getDataLongs(2)
    }

    override fun getMeta(): MetricsMetaInfo {
        return MetricsMetaInfo("endpoint_cpm", 3, entityId)
    }

    override fun toHour(): Metrics {
        val var1 = EndpointCpmMetrics()
        var1.entityId = entityId
        var1.serviceId = serviceId
        var1.value = value
        var1.total = total
        var1.timeBucket = toTimeBucketInHour()
        return var1
    }

    override fun toDay(): Metrics {
        val var1 = EndpointCpmMetrics()
        var1.entityId = entityId
        var1.serviceId = serviceId
        var1.value = value
        var1.total = total
        var1.timeBucket = toTimeBucketInDay()
        return var1
    }

    override fun remoteHashCode(): Int {
        var result = 17
        result = 31 * result + entityId.hashCode()
        return result
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (entityId?.hashCode() ?: 0)
        result = 31 * result + (serviceId?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false
        other as EndpointCpmMetrics
        if (entityId != other.entityId) return false
        if (serviceId != other.serviceId) return false
        return true
    }
}
