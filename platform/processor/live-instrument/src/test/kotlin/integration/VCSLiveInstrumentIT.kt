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
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import spp.probe.ProbeConfiguration
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.platform.general.Service
import spp.protocol.service.listen.addBreakpointHitListener
import java.util.concurrent.atomic.AtomicInteger

@Isolated
class VCSLiveInstrumentIT : LiveInstrumentIntegrationTest() {

    private fun doTest() {
        startEntrySpan("doTest")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `listen to versioned breakpoint hits by instrument`(): Unit = runBlocking {
        setupLineLabels {
            doTest()
        }

        val probeId = ProbeConfiguration.PROBE_ID
        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("git_commit", "test1")
            )
        ).await()
        delay(2000)

        val hitCount = AtomicInteger()
        var testContext = VertxTestContext()
        val instrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    VCSLiveInstrumentIT::class.java.name,
                    getLineNumber("done"),
                    Service.fromName("spp-test-probe")
                ),
                applyImmediately = true,
                id = testNameAsUniqueInstrumentId,
                hitLimit = 2
            )
        ).await()
        vertx.addBreakpointHitListener(instrument.id!!) { bpHit ->
            log.info("Received breakpoint hit: $bpHit")
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(1, topFrame.variables.size)
            }

            if (hitCount.incrementAndGet() == 1) {
                testContext.verify {
                    assertEquals(
                        instrument.location.service?.name +
                                "|" + instrument.location.service?.environment.toString() +
                                "|" + "test1",
                        bpHit.service
                    )
                }
            } else {
                testContext.verify {
                    assertEquals(
                        instrument.location.service?.name +
                                "|" + instrument.location.service?.environment.toString() +
                                "|" + "test2",
                        bpHit.service
                    )
                }
            }
            testContext.completeNow()
        }.await()

        doTest()
        errorOnTimeout(testContext)
        testContext = VertxTestContext()

        managementService.updateActiveProbeMetadata(
            probeId,
            JsonObject().put(
                "application",
                JsonObject().put("git_commit", "test2")
            )
        ).await()
        delay(2000)

        doTest()
        errorOnTimeout(testContext)
    }
}
