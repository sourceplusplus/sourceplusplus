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
package integration.log

import integration.LiveInstrumentIntegrationTest
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.event.LiveLogHit
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.listen.LiveInstrumentListener
import spp.protocol.service.listen.addLiveInstrumentListener

class FormatLiveLogTest : LiveInstrumentIntegrationTest() {

    @Suppress("UNUSED_VARIABLE")
    private fun formatLiveLog() {
        startEntrySpan("formatLiveLog")
        val i = 0
        val c = 'h'
        val s = "hello"
        val b = true
        val f = 1.0f
        val n = null
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `format primitives`() = runBlocking {
        setupLineLabels {
            formatLiveLog()
        }

        val instrumentId = "log-format-primitives"
        val format = "{} {} {} {} {} {}"
        val args = listOf("i", "c", "s", "b", "f", "n")

        val testContext = VertxTestContext()
        vertx.addLiveInstrumentListener(instrumentId, object : LiveInstrumentListener {
            override fun onLogHitEvent(event: LiveLogHit) {
                testContext.verify {
                    assertEquals(1, event.logResult.logs.size)
                    val log = event.logResult.logs[0]
                    assertEquals(format, log.content)
                    assertEquals("0 h hello true 1.0 null", log.toFormattedMessage())
                }
                testContext.completeNow()
            }
        }).await()

        //add live log
        assertNotNull(
            instrumentService.addLiveInstrument(
                LiveLog(
                    format,
                    args,
                    location = LiveSourceLocation(
                        FormatLiveLogTest::class.java.name,
                        getLineNumber("done"),
                        "spp-test-probe"
                    ),
                    applyImmediately = true,
                    id = instrumentId
                )
            ).await()
        )

        //trigger live log
        formatLiveLog()

        errorOnTimeout(testContext)
    }
}
