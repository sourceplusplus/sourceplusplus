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

import org.joor.Reflect

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
}
