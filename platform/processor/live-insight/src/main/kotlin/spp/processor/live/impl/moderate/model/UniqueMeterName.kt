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
package spp.processor.live.impl.moderate.model

import org.apache.skywalking.oap.server.telemetry.api.MetricsTag
import spp.protocol.insight.InsightType
import java.util.*

data class UniqueMeterName(
    val type: InsightType,
    val tagKeys: MetricsTag.Keys,
    val tagValues: MetricsTag.Values,
    val methodName: String? = null,
    val meterName: String? = null
) {
    override fun hashCode(): Int {
        return Objects.hash(meterName, tagKeys.keys.joinToString(","), tagValues.values.joinToString(","))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UniqueMeterName
        if (meterName != other.meterName) return false
        if (tagKeys.keys.joinToString(",") != other.tagKeys.keys.joinToString(",")) return false
        if (tagValues.values.joinToString(",") != other.tagValues.values.joinToString(",")) return false
        return true
    }

    override fun toString(): String {
        return "$meterName:${tagKeys.keys.joinToString(",")}=${tagValues.values.joinToString(",")}"
    }
}
