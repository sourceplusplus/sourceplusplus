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
package spp.platform.storage.config

import spp.platform.common.ClusterConnection
import spp.platform.storage.SourceStorage
import spp.platform.storage.config.SystemConfiguration.ConfigChangeMapper
import spp.platform.storage.config.SystemConfiguration.ConfigChangeValidator
import java.util.concurrent.atomic.AtomicReference

class SystemConfiguration<T>(
    val name: String,
    val defaultValue: T,
    val validator: ConfigChangeValidator = ConfigChangeValidator { },
    val mapper: ConfigChangeMapper<T> = ConfigChangeMapper { it as T }
) {
    private val reference = AtomicReference(getEnv()?.let { mapper.mapper(it) } ?: defaultValue)
    private val changeListeners = mutableListOf<(T) -> Unit>()

    fun get(): T {
        return reference.get()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun retrieve(): T {
        val value: Any? = SourceStorage.get("configuration:$name")
        return value as T? ?: defaultValue
    }

    suspend fun set(value: T) {
        SourceStorage.put("configuration:$name", value as Any)
        reference.set(value)
        changeListeners.forEach { it(value) }
    }

    suspend fun set(value: String) {
        set(mapper.mapper(value))
    }

    suspend fun install() {
        val initial = retrieve()
        if (initial != null && initial != defaultValue) {
            set(initial)
        }

        SystemConfig.register(this)
    }

    fun addChangeListener(listener: (T) -> Unit) {
        changeListeners.add(listener)
    }

    private fun getEnv(): String? {
        return ClusterConnection.getConfig(name)
    }

    fun interface ConfigChangeValidator {
        fun validateChange(value: String)
    }

    fun interface ConfigChangeMapper<T> {
        fun mapper(value: String): T
    }

    override fun toString(): String = name
}
