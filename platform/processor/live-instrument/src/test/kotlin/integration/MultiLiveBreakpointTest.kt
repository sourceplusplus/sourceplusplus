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

import io.vertx.core.Promise
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.artifact.exception.sourceAsLineNumber
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.marshall.ProtocolMarshaller

@Suppress("unused")
class MultiLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun addSideBySide() {
        val activeSpan = startEntrySpan("addSideBySide")
        addLineLabel("line1") { Throwable().stackTrace[0].lineNumber }
        addLineLabel("line2") { Throwable().stackTrace[0].lineNumber }
        stopSpan(activeSpan)
    }

    @Test
    fun `side by side`() = runBlocking {
        setupLineLabels {
            addSideBySide()
        }

        val gotLine1Promise = Promise.promise<Void>()
        val gotLine2Promise = Promise.promise<Void>()
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

                    if (topFrame.sourceAsLineNumber() == getLineNumber("line1")) {
                        if (gotLine1Promise.future().isComplete) {
                            gotLine1Promise.fail("got line1 twice")
                        } else {
                            gotLine1Promise.complete()
                        }
                    } else if (topFrame.sourceAsLineNumber() == getLineNumber("line2")) {
                        if (gotLine2Promise.future().isComplete) {
                            gotLine2Promise.fail("got line2 twice")
                        } else {
                            gotLine2Promise.complete()
                        }
                    } else {
                        fail("Unexpected line number: ${topFrame.sourceAsLineNumber()}")
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
                            MultiLiveBreakpointTest::class.qualifiedName!!,
                            getLineNumber("line1"),
                            //"spp-test-probe" //todo: impl this so applyImmediately can be used
                        ),
                        hitLimit = 2,
                        //applyImmediately = true //todo: can't use applyImmediately
                    ),
                    LiveBreakpoint(
                        location = LiveSourceLocation(
                            MultiLiveBreakpointTest::class.qualifiedName!!,
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
                    addSideBySide()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        gotLine1Promise.future().await()
        gotLine2Promise.future().await()

        //clean up after test
        val removedInstruments = instrumentService.removeLiveInstruments(
            LiveSourceLocation(MultiLiveBreakpointTest::class.qualifiedName!!)
        ).await()
        testContext.verify {
            assertEquals(2, removedInstruments.size)
        }

        if (testContext.failed()) {
            throw testContext.causeOfFailure()
        }
    }
}
