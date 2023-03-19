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
package spp.processor.live.impl.util

import com.intellij.psi.PsiFile
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.processor.live.impl.moderate.InsightModerator
import spp.processor.live.impl.moderate.model.LiveInsightRequest
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.insight.InsightType
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import java.time.Instant

class BoundedTreeSetTest {

    @Test
    fun `no duplicates`() {
        val set = BoundedTreeSet<LiveInsightRequest>(1000)

        val request1LowPriority = LiveInsightRequest(
            LiveMeter(
                id = "insight-function-duration:" + "testMethod()",
                meterType = MeterType.METHOD_TIMER,
                metricValue = MetricValue(MetricValueType.NUMBER, "1"),
                location = LiveSourceLocation("testMethod()", -1)
            ),
            "workspaceId",
            object : InsightModerator() {
                override val type: InsightType
                    get() = throw IllegalStateException()

                override suspend fun addAvailableInsights(
                    psiFile: PsiFile,
                    artifact: ArtifactQualifiedName,
                    insights: JsonObject
                ) = Unit
            },
            5L,
            Instant.now()
        )
        set.add(request1LowPriority)
        set.add(request1LowPriority)
        assertEquals(1, set.size)

        val request1HighPriority = request1LowPriority.copy(priority = 10L)
        set.add(request1HighPriority)
        set.add(request1HighPriority)
        assertEquals(1, set.size)

        assertEquals(10L, set.first().priority)
    }
}
