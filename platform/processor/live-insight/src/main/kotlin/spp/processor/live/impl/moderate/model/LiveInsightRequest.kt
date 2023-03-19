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

import spp.processor.live.impl.moderate.InsightModerator
import spp.protocol.instrument.LiveInstrument
import java.time.Instant

/**
 * Represents an automated request to collect live insight data.
 */
data class LiveInsightRequest(
    val liveInstrument: LiveInstrument,
    val workspaceId: String,
    val moderator: InsightModerator,
    val priority: Long,
    val timestamp: Instant
) : Comparable<LiveInsightRequest> {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LiveInsightRequest
        if (liveInstrument != other.liveInstrument) return false
        return true
    }

    override fun hashCode(): Int {
        return liveInstrument.hashCode()
    }

    /**
     * If objects are equal, they are considered equal.
     * If objects are not equal, they are compared by priority.
     */
    override fun compareTo(other: LiveInsightRequest): Int {
        if (this == other) return 0
        return priority.compareTo(other.priority)
    }
}
