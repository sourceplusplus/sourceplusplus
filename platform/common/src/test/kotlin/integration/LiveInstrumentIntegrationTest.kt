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

import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import mu.KotlinLogging
import org.joor.Reflect
import spp.protocol.instrument.event.LiveBreakpointHit
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.event.LiveInstrumentEventType
import spp.protocol.instrument.event.LiveLogHit
import spp.protocol.service.SourceServices.Subscribe.toLiveInstrumentSubscriberAddress
import java.util.concurrent.atomic.AtomicInteger

abstract class LiveInstrumentIntegrationTest : PlatformIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

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

    fun onBreakpointHit(hitLimit: Int = 1, invoke: (LiveBreakpointHit) -> Unit): MessageConsumer<*> {
        val hitCount = AtomicInteger(0)
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        return consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.BREAKPOINT_HIT) {
                val bpHit = LiveBreakpointHit(JsonObject(event.data))
                invoke.invoke(bpHit)

                if (hitCount.incrementAndGet() == hitLimit) {
                    consumer.unregister()
                }
            } else {
                log.debug { "Ignoring event: $event" }
            }
        }
    }

    fun onLogHit(invoke: (LiveLogHit) -> Unit): MessageConsumer<*> {
        val consumer = vertx.eventBus().consumer<Any>(toLiveInstrumentSubscriberAddress("system"))
        return consumer.handler {
            val event = Json.decodeValue(it.body().toString(), LiveInstrumentEvent::class.java)
            if (event.eventType == LiveInstrumentEventType.LOG_HIT) {
                val logHit = LiveLogHit(JsonObject(event.data))
                invoke.invoke(logHit)
                consumer.unregister()
            } else {
                log.debug { "Ignoring event: $event" }
            }
        }
    }
}
