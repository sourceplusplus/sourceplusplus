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

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spp.platform.common.DeveloperAuth
import spp.platform.storage.ExpiringSharedData
import spp.platform.storage.RedisStorage
import java.util.concurrent.TimeUnit

class ExpiringSharedDataITTest : PlatformIntegrationTest() {

    @Test
    fun `expire after write`() = runBlocking {
        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        val sharedData = ExpiringSharedData.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build<String, String>("test1", vertx, storage)
        val key = "key"
        val value = "value"
        sharedData.put(key, value)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(2_000)
        assertNull(sharedData.getIfPresent(key))
    }

    @Test
    fun `expire after access`() = runBlocking {
        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        val sharedData = ExpiringSharedData.newBuilder()
            .expireAfterAccess(1, TimeUnit.SECONDS)
            .build<String, String>("test2", vertx, storage)
        val key = "key"
        val value = "value"
        sharedData.put(key, value)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(500)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(500)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(500)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(2_000)
        assertNull(sharedData.getIfPresent(key))
    }

    @Test
    fun `expire after write with Pair`() = runBlocking {
        DatabindCodec.mapper().registerModule(KotlinModule())

        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        val sharedData = ExpiringSharedData.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build<String, Pair<String, String>>("test3", vertx, storage)
        val key = "key"
        val value = "first" to "second"
        sharedData.put(key, value)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(2_000)
        assertNull(sharedData.getIfPresent(key))

        val buffer = JsonObject.mapFrom(value).toBuffer()
        val value2 = JsonObject(buffer).mapTo(Pair::class.java)
        assertEquals(value, value2)
    }

    @Test
    fun `expire after write with DeveloperAuth`() = runBlocking {
        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        val sharedData = ExpiringSharedData.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .build<String, DeveloperAuth>("test4", vertx, storage)
        val key = "key"
        val value = DeveloperAuth("selfId", "accessToken")
        sharedData.put(key, value)
        assertEquals(value, sharedData.getIfPresent(key))
        delay(2_000)
        assertNull(sharedData.getIfPresent(key))

        val buffer = Buffer.buffer()
        value.writeToBuffer(buffer)
        val readValue = DeveloperAuth("test", "test")
            .apply { readFromBuffer(0, buffer) }
        assertEquals(value, readValue)
    }
}
