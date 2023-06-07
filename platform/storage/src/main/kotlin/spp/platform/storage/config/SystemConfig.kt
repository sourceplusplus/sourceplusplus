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

import io.vertx.core.impl.ConcurrentHashSet
import java.util.stream.Stream

object SystemConfig {

    private val registeredConfigurations = ConcurrentHashSet<SystemConfiguration<*>>()

    fun <T> register(config: SystemConfiguration<T>): SystemConfiguration<T> {
        require(registeredConfigurations.none { it.name == config.name }) {
            "System config ${config.name} already registered"
        }

        registeredConfigurations.add(config)
        return config
    }

    fun isValidConfig(config: String): Boolean {
        return registeredConfigurations.any { it.name == config }
    }

    fun values(): Stream<SystemConfiguration<*>> {
        return registeredConfigurations.stream()
    }

    fun get(config: String): SystemConfiguration<*> {
        return registeredConfigurations.firstOrNull { it.name == config }
            ?: throw IllegalArgumentException("System config $config not found")
    }
}
