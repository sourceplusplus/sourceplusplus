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
package integration

import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("UNUSED_VARIABLE", "unused")
class PrimitiveConditionITTest : LiveInstrumentIntegrationTest() {

    companion object {
        const val fieldI = 100
    }

    val instanceI = 100

    private fun primitiveStaticVariable() {
        startEntrySpan("primitiveStaticVariable")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    private fun primitiveInstanceVariable() {
        startEntrySpan("primitiveInstanceVariable")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    private fun primitiveLocalVariable() {
        startEntrySpan("primitiveLocalVariable")
        val localI = 100
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `primitive static variable`() = runBlocking {
        setupLineLabels {
            primitiveStaticVariable()
        }

        val testContext = VertxTestContext()
        onBreakpointHit {
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    PrimitiveConditionITTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                condition = "staticFields[fieldI] == 100",
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        primitiveStaticVariable()

        errorOnTimeout(testContext)
    }

    @Test
    fun `instance local variable`() = runBlocking {
        setupLineLabels {
            primitiveInstanceVariable()
        }

        val testContext = VertxTestContext()
        onBreakpointHit {
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    PrimitiveConditionITTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                condition = "fields[instanceI] == 100",
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        primitiveInstanceVariable()

        errorOnTimeout(testContext)
    }

    @Test
    fun `primitive local variable`() = runBlocking {
        setupLineLabels {
            primitiveLocalVariable()
        }

        val testContext = VertxTestContext()
        onBreakpointHit {
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    PrimitiveConditionITTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                condition = "localVariables[localI] == 100",
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        primitiveLocalVariable()

        errorOnTimeout(testContext)
    }
}
