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

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.kotlin.coroutines.await
import io.vertx.redis.client.impl.types.NumberType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.RepeatedTest
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
            .build<String, String>("test1", storage)
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
            .build<String, String>("test2", storage)
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
            .build<String, Pair<String, String>>("test3", storage)
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
            .build<String, DeveloperAuth>("test4", storage)
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

    @RepeatedTest(2)
    fun `ensure lock lease time works`() = runBlocking {
        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        val sharedData = ExpiringSharedData.newBuilder().build<String, String>("lease-test", storage)
        val lockName = "lock-test-" + System.currentTimeMillis()
        sharedData.getLock(lockName, 1000)

        //try to acquire lock
        try {
            sharedData.getLock(lockName, 1000)
            fail("Should not be able to acquire lock")
        } catch (e: Exception) {
            assertTrue(e.message!!.startsWith("Timed out waiting to get lock"))
        }

        //wait for lock to expire
        delay(7_500)

        //try to acquire lock again
        val lock2 = sharedData.getLock(lockName, 1000)
        lock2.release()

        //clean up locks
        sharedData.cleanup()
    }

    @Test
    fun `ensure locks have ttl`(): Unit = runBlocking {
        val storage = RedisStorage(vertx)
        storage.init(JsonObject().put("host", "localhost").put("port", 6379))

        val sharedData = ExpiringSharedData.newBuilder().build<String, String>("ttl-test", storage)
        val lockName = "lock-test-" + System.currentTimeMillis()
        sharedData.getLock(lockName, 1000)

        val ttl = storage.redis.ttl("cluster:__vertx:locks:expiring_shared_data:ttl-test:lock:$lockName").await()
        log.info("ttl: {}", ttl)

        assertTrue(ttl is NumberType)
        assertTrue((ttl as NumberType).toNumber().toInt() > 0)
    }
}
