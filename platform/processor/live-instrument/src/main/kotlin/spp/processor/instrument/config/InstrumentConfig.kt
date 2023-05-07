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
package spp.processor.instrument.config

import spp.platform.storage.config.SystemConfiguration

object InstrumentConfig {

    suspend fun install() {
        LogPublishRateLimit.install()
        LogPublishCacheTTL.install()
    }

    val LogPublishRateLimit = SystemConfiguration(
        "spp-platform.instrument.log_publish_rate_limit",
        defaultValue = 1000L,
        validator = { it.toLong() },
        mapper = { it.toLong() }
    )
    val LogPublishCacheTTL = SystemConfiguration(
        "spp-platform.instrument.log_publish_cache_ttl",
        defaultValue = 60_000L,
        validator = { it.toLong() },
        mapper = { it.toLong() }
    )
}
