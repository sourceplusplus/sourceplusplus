/*
 * Source++, the open-source live coding platform.
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
package integration

import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveSourceLocation

@Suppress("UNUSED_VARIABLE", "unused")
class FormatLiveLogTest : LiveInstrumentIntegrationTest() {

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

        val format = "{} {} {} {} {} {}"
        val args = listOf("i", "c", "s", "b", "f", "n")

        val testContext = VertxTestContext()
        onLogHit {
            testContext.verify {
                assertEquals(1, it.logResult.logs.size)
                val log = it.logResult.logs[0]
                assertEquals(format, log.content)
                assertEquals("0 h hello true 1.0 null", log.toFormattedMessage())
            }
            testContext.completeNow()
        }

        //add live log
        instrumentService.addLiveInstrument(
            LiveLog(
                format,
                args,
                location = LiveSourceLocation(
                    FormatLiveLogTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    //"spp-test-probe" //todo: impl this so applyImmediately can be used
                ),
                hitLimit = 1
                //applyImmediately = true //todo: can't use applyImmediately
            )
        ).await()

        //trigger live log
        vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
            formatLiveLog()
        }

        errorOnTimeout(testContext)
    }
}
