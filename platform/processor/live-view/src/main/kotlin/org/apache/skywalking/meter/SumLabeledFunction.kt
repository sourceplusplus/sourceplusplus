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
package org.apache.skywalking.meter

import org.apache.skywalking.oap.server.core.UnexpectedException
import org.apache.skywalking.oap.server.core.analysis.manual.instance.InstanceTraffic
import org.apache.skywalking.oap.server.core.analysis.meter.Meter
import org.apache.skywalking.oap.server.core.analysis.meter.MeterEntity
import org.apache.skywalking.oap.server.core.analysis.meter.function.AcceptableValue
import org.apache.skywalking.oap.server.core.analysis.meter.function.MeterFunction
import org.apache.skywalking.oap.server.core.analysis.metrics.DataTable
import org.apache.skywalking.oap.server.core.analysis.metrics.LabeledValueHolder
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.annotation.Entrance
import org.apache.skywalking.oap.server.core.analysis.metrics.annotation.SourceFrom
import org.apache.skywalking.oap.server.core.remote.grpc.proto.RemoteData
import org.apache.skywalking.oap.server.core.storage.StorageID
import org.apache.skywalking.oap.server.core.storage.annotation.BanyanDB.MeasureField
import org.apache.skywalking.oap.server.core.storage.annotation.BanyanDB.SeriesID
import org.apache.skywalking.oap.server.core.storage.annotation.Column
import org.apache.skywalking.oap.server.core.storage.type.Convert2Entity
import org.apache.skywalking.oap.server.core.storage.type.Convert2Storage
import org.apache.skywalking.oap.server.core.storage.type.StorageBuilder
import java.util.*

@MeterFunction(functionName = "sumLabeled")
abstract class SumLabeledFunction : Meter(), AcceptableValue<DataTable>, LabeledValueHolder {

    companion object {
        protected const val VALUE = "datatable_value"
    }

    @Column(name = ENTITY_ID, length = 512)
    @SeriesID(index = 0)
    private var entityId: String? = null

    @Column(name = InstanceTraffic.SERVICE_ID)
    private var serviceId: String? = null

    @Column(name = VALUE, dataType = Column.ValueDataType.LABELED_VALUE, storageOnly = true)
    @MeasureField
    private var value = DataTable(30)

    override fun getEntityId(): String {
        return entityId!!
    }

    fun setEntityId(entityId: String?) {
        this.entityId = entityId
    }

    fun getServiceId(): String {
        return serviceId!!
    }

    fun setServiceId(serviceId: String?) {
        this.serviceId = serviceId
    }

    override fun getValue(): DataTable {
        return value
    }

    fun setValue(value: DataTable) {
        this.value = value
    }

    @Entrance
    fun combine(@SourceFrom value: DataTable?) {
        this.value.append(value)
    }

    override fun accept(entity: MeterEntity, value: DataTable?) {
        setEntityId(entity.id())
        serviceId = entity.serviceId()
        this.value.append(value)
    }

    override fun builder(): Class<out StorageBuilder<*>?> {
        return SumLabeledStorageBuilder::class.java
    }

    override fun combine(metrics: Metrics): Boolean {
        val sumLabeledFunction = metrics as SumLabeledFunction
        combine(sumLabeledFunction.getValue())
        return true
    }

    override fun calculate() {
        for (key in value.keys()) {
            val value = value[key]
            if (Objects.isNull(value)) {
                continue
            }
            this.value.put(key, value)
        }
    }

    override fun toHour(): Metrics {
        val metrics = createNew() as SumLabeledFunction
        metrics.setEntityId(getEntityId())
        metrics.timeBucket = toTimeBucketInHour()
        metrics.serviceId = serviceId
        metrics.getValue().copyFrom(getValue())
        return metrics
    }

    override fun toDay(): Metrics {
        val metrics = createNew() as SumLabeledFunction
        metrics.setEntityId(getEntityId())
        metrics.timeBucket = toTimeBucketInDay()
        metrics.serviceId = serviceId
        metrics.getValue().copyFrom(getValue())
        return metrics
    }

    override fun id0(): StorageID {
        return StorageID()
            .append(TIME_BUCKET, timeBucket)
            .append(ENTITY_ID, getEntityId())
    }

    override fun deserialize(remoteData: RemoteData) {
        setValue(DataTable(remoteData.getDataObjectStrings(0)))
        timeBucket = remoteData.getDataLongs(0)
        setEntityId(remoteData.getDataStrings(0))
        serviceId = remoteData.getDataStrings(1)
    }

    override fun serialize(): RemoteData.Builder {
        val remoteBuilder = RemoteData.newBuilder()
        remoteBuilder.addDataObjectStrings(value.toStorageData())
        remoteBuilder.addDataLongs(timeBucket)
        remoteBuilder.addDataStrings(getEntityId())
        remoteBuilder.addDataStrings(serviceId)
        return remoteBuilder
    }

    override fun remoteHashCode(): Int {
        return getEntityId().hashCode()
    }

    class SumLabeledStorageBuilder : StorageBuilder<SumLabeledFunction> {
        override fun storage2Entity(converter: Convert2Entity): SumLabeledFunction {
            val metrics: SumLabeledFunction = object : SumLabeledFunction() {
                override fun createNew(): AcceptableValue<DataTable?>? {
                    throw UnexpectedException("createNew should not be called")
                }
            }
            metrics.setValue(DataTable(converter[VALUE] as String))
            metrics.timeBucket = (converter[TIME_BUCKET] as Number).toLong()
            metrics.serviceId = converter[InstanceTraffic.SERVICE_ID] as String
            metrics.setEntityId(converter[ENTITY_ID] as String)
            return metrics
        }

        override fun entity2Storage(storageData: SumLabeledFunction, converter: Convert2Storage<*>) {
            converter.accept(VALUE, storageData.getValue())
            converter.accept(TIME_BUCKET, storageData.timeBucket)
            converter.accept(InstanceTraffic.SERVICE_ID, storageData.serviceId)
            converter.accept(ENTITY_ID, storageData.getEntityId())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SumLabeledFunction) return false
        return entityId == other.entityId && timeBucket == other.timeBucket
    }

    override fun hashCode(): Int {
        return Objects.hash(entityId, timeBucket)
    }
}
