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

import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import org.joor.Reflect
import spp.protocol.SourceServices.Provide.toLiveInstrumentSubscriberAddress
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveLogHit
import spp.protocol.marshall.ProtocolMarshaller
import java.util.concurrent.TimeUnit

abstract class LiveInstrumentIntegrationTest : PlatformIntegrationTest() {

    private val lineLabels = mutableMapOf<String, Int>()
    private var setupLineLabels = false

    fun addLineLabel(label: String, getLineNumber: () -> Int) {
        lineLabels[label] = getLineNumber.invoke()
    }

    fun getLineNumber(label: String): Int {
        return lineLabels[label] ?: throw IllegalArgumentException("No line label found for $label")
    }

    fun setupLineLabels(invoke: () -> Unit) {
        setupLineLabels = true
        invoke.invoke()
        setupLineLabels = false
    }

    fun stopSpan() {
        if (setupLineLabels) {
            return
        }

        Reflect.onClass(
            "org.apache.skywalking.apm.agent.core.context.ContextManager"
        ).call("stopSpan")
    }

    fun startEntrySpan(name: String) {
        if (setupLineLabels) {
            return
        }

        val contextCarrier = Reflect.onClass(
            "org.apache.skywalking.apm.agent.core.context.ContextCarrier"
        ).create().get<Any>()
        Reflect.onClass(
            "org.apache.skywalking.apm.agent.core.context.ContextManager"
        ).call("createEntrySpan", name, contextCarrier)
    }

    fun onBreakpointHit(invoke: (LiveBreakpointHit) -> Unit): MessageConsumer<*> {
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        return consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                val bpHit = ProtocolMarshaller.deserializeLiveBreakpointHit(JsonObject(event.data))
                invoke.invoke(bpHit)
                consumer.unregister()
            }
        }
    }

    fun onLogHit(invoke: (LiveLogHit) -> Unit): MessageConsumer<*> {
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        return consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.LOG_HIT) {
                val logHit = ProtocolMarshaller.deserializeLiveLogHit(JsonObject(event.data))
                invoke.invoke(logHit)
                consumer.unregister()
            }
        }
    }

    fun errorOnTimeout(testContext: VertxTestContext, waitTime: Long = 15) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    fun successOnTimeout(testContext: VertxTestContext, waitTime: Long = 15) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            testContext.completeNow()
        }
    }
}
