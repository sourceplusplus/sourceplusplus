package spp.processor.live.impl.moderate

import com.intellij.psi.PsiFile
import com.intellij.util.containers.IntObjectMap
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import com.intellij.util.ui.update.Update.LOW_PRIORITY
import io.vertx.core.json.JsonObject
import org.joor.Reflect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.processor.live.impl.moderate.model.LiveInsightRequest
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.insight.InsightType
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import java.time.Instant

class WorkspaceInsightQueueTest {

    @Test
    fun `test dupe request`() {
        val queue = MergingUpdateQueue(
            "test-queue", 1000,
            true, null, null, null, false
        )
        queue.suspend()

        val request1 = LiveInsightRequest(
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
        queue.queue(Update.create(request1) {})
        queue.queue(Update.create(request1) {})
        assertEquals(1, Reflect.on(queue).get<IntObjectMap<Map<*, *>>>("myScheduledUpdates").get(LOW_PRIORITY).size)

        val request2 = LiveInsightRequest(
            LiveMeter(
                id = "insight-function-duration:" + "testMethod2()",
                meterType = MeterType.METHOD_TIMER,
                metricValue = MetricValue(MetricValueType.NUMBER, "1"),
                location = LiveSourceLocation("testMethod2()", -1)
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
        queue.queue(Update.create(request2) {})
        queue.queue(Update.create(request2) {})
        assertEquals(2, Reflect.on(queue).get<IntObjectMap<Map<*, *>>>("myScheduledUpdates").get(LOW_PRIORITY).size)
    }
}
