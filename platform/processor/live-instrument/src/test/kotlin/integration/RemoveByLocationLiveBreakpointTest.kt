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

import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.marshall.ProtocolMarshaller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("unused", "UNUSED_VARIABLE")
class RemoveByLocationLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun removeMultipleByLine() {
        startEntrySpan("removeMultipleByLine")
        val line1Var = 1
        addLineLabel("line1") { Throwable().stackTrace[0].lineNumber }
        val line2Var = 2
        addLineLabel("line2") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `remove multiple by line number`() = runBlocking {
        setupLineLabels {
            removeMultipleByLine()
        }

        val gotAllHitsLatch = CountDownLatch(2)
        val testContext = VertxTestContext()
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                //verify live breakpoint data
                val bpHit = ProtocolMarshaller.deserializeLiveBreakpointHit(JsonObject(event.data))
                testContext.verify {
                    assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                    val topFrame = bpHit.stackTrace.elements.first()

                    if (topFrame.variables.find { it.name == "line2Var" } != null) {
                        gotAllHitsLatch.countDown()
                    } else {
                        assertNotNull(topFrame.variables.find { it.name == "line1Var" })
                        gotAllHitsLatch.countDown()
                    }
                }
            }
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause())
                return@completionHandler
            }

            //add live breakpoint
            instrumentService.addLiveInstruments(
                listOf(
                    LiveBreakpoint(
                        location = LiveSourceLocation(
                            RemoveByLocationLiveBreakpointTest::class.qualifiedName!!,
                            getLineNumber("line1"),
                            //"spp-test-probe" //todo: impl this so applyImmediately can be used
                        ),
                        hitLimit = 2,
                        //applyImmediately = true //todo: can't use applyImmediately
                    ),
                    LiveBreakpoint(
                        location = LiveSourceLocation(
                            RemoveByLocationLiveBreakpointTest::class.qualifiedName!!,
                            getLineNumber("line2"),
                            //"spp-test-probe" //todo: impl this so applyImmediately can be used
                        ),
                        hitLimit = 2,
                        //applyImmediately = true //todo: can't use applyImmediately
                    )
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    removeMultipleByLine()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        if (!gotAllHitsLatch.await(30, TimeUnit.SECONDS)) {
            testContext.failNow(RuntimeException("didn't get all hits"))
        }
        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }

        //remove line1 breakpoint by line number
        val removedInstruments = instrumentService.removeLiveInstruments(
            LiveSourceLocation(
                RemoveByLocationLiveBreakpointTest::class.qualifiedName!!,
                getLineNumber("line1"),
            )
        ).await()
        testContext.verify {
            assertEquals(1, removedInstruments.size)
            assertEquals(getLineNumber("line1"), removedInstruments.first().location.line)
        }

        //ensure line1 is removed and line2 is still there
        val remainingInstruments = instrumentService.getLiveInstrumentsByLocation(
            LiveSourceLocation(RemoveByLocationLiveBreakpointTest::class.qualifiedName!!)
        ).await()
        testContext.verify {
            assertEquals(1, remainingInstruments.size)

            val line2Breakpoint = remainingInstruments.first()
            assertTrue(line2Breakpoint.location.line == getLineNumber("line2"))
        }

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }
}
