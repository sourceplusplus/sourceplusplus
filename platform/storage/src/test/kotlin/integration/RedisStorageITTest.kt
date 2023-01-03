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

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.BeforeAll
import spp.platform.common.ClusterConnection
import spp.platform.storage.RedisStorage

class RedisStorageITTest : BaseStorageITTest<RedisStorage>() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            ClusterConnection.config = baseConfig
                .put(
                    "storage",
                    JsonObject()
                        .put("selector", "redis")
                        .put("redis", JsonObject())
                )
        }
    }

    override suspend fun createInstance(vertx: Vertx): RedisStorage {
        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        return storage
    }
}
