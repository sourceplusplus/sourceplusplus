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
