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

import io.vertx.core.Vertx
import org.joor.Reflect

abstract class LiveInstrumentIntegrationTest : PlatformIntegrationTest() {

    private val lineLabels = mutableMapOf<String, Int>()

    fun addLineLabel(label: String, getLineNumber: () -> Int) {
        lineLabels[label] = getLineNumber.invoke()
    }

    fun getLineNumber(label: String): Int {
        return lineLabels[label] ?: throw IllegalArgumentException("No line label found for $label")
    }

    fun setupLineLabels(invoke: () -> Unit) {
        vertx.runOnContext {
            Vertx.currentContext().put("setupLineLabels", true)
            invoke.invoke()
        }
    }

    fun stopSpan(activeSpan: Any?) {
        if (Vertx.currentContext()?.get<Boolean>("setupLineLabels") == true) {
            return
        }

        Reflect.onClass(
            "org.apache.skywalking.apm.agent.core.context.ContextManager"
        ).call("stopSpan", activeSpan)
    }

    fun startEntrySpan(name: String): Any? {
        if (Vertx.currentContext()?.get<Boolean>("setupLineLabels") == true) {
            return null
        }

        val contextCarrier = Reflect.onClass(
            "org.apache.skywalking.apm.agent.core.context.ContextCarrier"
        ).create().get<Any>()
        return Reflect.onClass(
            "org.apache.skywalking.apm.agent.core.context.ContextManager"
        ).call("createEntrySpan", name, contextCarrier).get()
    }
}
