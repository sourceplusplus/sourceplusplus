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
package integration

import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.probe.ProbeConfiguration
import spp.protocol.artifact.metrics.MetricStep
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.platform.general.Service
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.rule.ViewRule
import java.time.Instant
import java.time.temporal.ChronoUnit

@Isolated
class VCSHistoricalViewIT : LiveInstrumentIntegrationTest() {

    private fun doTest() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @Test
    fun `vcs historical view`(): Unit = runBlocking {
        assumeTrue("true" == System.getProperty("test.includeSlow"))
        setupLineLabels {
            doTest()
        }

        val liveMeter = LiveMeter(
            MeterType.GAUGE,
            MetricValue(MetricValueType.NUMBER, "2"),
            location = LiveSourceLocation(
                VCSHistoricalViewIT::class.java.name,
                getLineNumber("done"),
                Service.fromName("spp-test-probe")
            ),
            id = testNameAsUniqueInstrumentId,
            applyImmediately = true
        )

        val rule = viewService.saveRule(
            ViewRule(
                name = liveMeter.id!!,
                exp = buildString {
                    append("(")
                    append(liveMeter.id)
                    append(".downsampling(LATEST)")
                    append(").service(['service'], Layer.GENERAL)")
                },
                meterIds = listOf(liveMeter.id!!)
            )
        ).await()

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveMeter.id!!),
                viewConfig = LiveViewConfig("test", listOf(liveMeter.id!!)),
                service = Service.fromName("spp-test-probe")
            )
        ).await().subscriptionId!!

        instrumentService.addLiveInstrument(liveMeter).await()
        doTest()
        delay(75_000)

        //update commit id
        val probeId = ProbeConfiguration.PROBE_ID
        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("version", "test1")
            )
        ).await()
        delay(75_000)

        val stop = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val start = stop.minusSeconds(5 * 60L)
        val historicalView = viewService.getHistoricalMetrics(
            listOf(Service.fromName("spp-test-probe").id),
            listOf(liveMeter.id!!),
            MetricStep.MINUTE, start, stop
        ).await()
        assertTrue(historicalView.data.map { it as JsonObject }.any {
            val service = Service(it.getJsonObject("service"))
            it.getString("value") == "2" && service.commitId == "test"
        })
        assertTrue(historicalView.data.map { it as JsonObject }.any {
            val service = Service(it.getJsonObject("service"))
            it.getString("value") == "2" && service.commitId == "test1"
        })

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveMeter.id!!).await())
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
        assertNotNull(viewService.deleteRule(rule.name).await())
    }
}
