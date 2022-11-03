/*
 * Source++, the continuous feedback platform for developers.
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
package spp.processor.live.impl.view.model

import io.vertx.core.buffer.Buffer
import io.vertx.core.shareddata.ClusterSerializable
import org.apache.skywalking.oap.server.core.analysis.metrics.*
import org.apache.skywalking.oap.server.core.remote.grpc.proto.RemoteData
import spp.platform.common.util.NoArg
import java.io.ByteArrayOutputStream

@NoArg
class ClusterMetrics(var metrics: Metrics) : ClusterSerializable {

    override fun writeToBuffer(buffer: Buffer) {
        buffer.appendShort(metrics.javaClass.name.length.toShort())
        buffer.appendString(metrics.javaClass.name)

        val byteStream = ByteArrayOutputStream()
        metrics.serialize().build().writeTo(byteStream)
        buffer.appendBytes(byteStream.toByteArray())
    }

    override fun readFromBuffer(pos: Int, buffer: Buffer): Int {
        var readPos = pos
        val metricsNameLength = buffer.getShort(pos)
        readPos += 2

        val metricsName = buffer.getString(readPos, readPos + metricsNameLength)
        readPos += metricsNameLength

        val metricsClass = Class.forName(metricsName)
        metrics = metricsClass.newInstance() as Metrics
        metrics.deserialize(RemoteData.parseFrom(buffer.getBytes(readPos, buffer.length())))
        readPos += buffer.length() - readPos
        return readPos
    }

    fun calculateAndGetValue(): Any {
        metrics.calculate()
        return when (metrics) {
            is IntValueHolder -> (metrics as IntValueHolder).value
            is DoubleValueHolder -> (metrics as DoubleValueHolder).value
            is LongValueHolder -> (metrics as LongValueHolder).value
            is MultiIntValuesHolder -> (metrics as MultiIntValuesHolder).values

            is LabeledValueHolder -> (metrics as LabeledValueHolder).value.let {
                val map = mutableMapOf<String, Any>()
                it.keys().forEach { key ->
                    map[key] = it[key]
                }
                map
            }

            else -> throw IllegalArgumentException("Unknown metrics type: ${metrics.javaClass.name}")
        }
    }
}
