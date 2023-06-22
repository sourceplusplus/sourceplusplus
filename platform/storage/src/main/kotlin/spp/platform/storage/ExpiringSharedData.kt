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
package spp.platform.storage

import io.vertx.core.Promise
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.coroutines.await
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

class ExpiringSharedData<K, V> private constructor(
    private val expireAfterWriteNanos: Long = -1,
    private val expireAfterAccessNanos: Long = -1,
    private val mapId: String,
    private val storage: CoreStorage
) {

    companion object {
        private val log = KotlinLogging.logger {}

        fun newBuilder(): Builder {
            return Builder()
        }
    }

    private suspend fun getLock(key: K): Lock {
        return getLock(key, -1)
    }

    suspend fun getLock(key: K, timeout: Long): Lock {
        val lockName = "expiring_shared_data:$mapId:lock:$key"
        val lock = try {
            storage.lock(lockName, timeout)
        } catch (e: Exception) {
            log.warn(e) { "Failed to acquire lock for key $key" }
            throw e
        }

        if (storage is RedisStorage) {
            //add ttl to lock
            storage.redis.expire(listOf("cluster:__vertx:locks:" + storage.namespace(lockName), "60"))
        }

        return lock
    }

    suspend fun getIfPresent(key: K): V? {
        cleanup()

        val backingMap = storage.map<K, V>("expiring_shared_data:$mapId:backing_map")
        val expirationMap = storage.map<K, Long>("expiring_shared_data:$mapId:expiration_map")
        val lock = getLock(key)
        try {
            val promise = Promise.promise<V?>()
            backingMap.get(key) { result ->
                if (result.succeeded()) {
                    val value = result.result()
                    if (value != null) {
                        if (expireAfterAccessNanos > 0) {
                            expirationMap.put(key, System.nanoTime()).onSuccess {
                                promise.complete(value)
                            }.onFailure {
                                promise.fail(it)
                            }
                        } else {
                            promise.complete(value)
                        }
                    } else {
                        promise.complete(null)
                    }
                } else {
                    promise.fail(result.cause())
                }
            }
            return promise.future().await()
        } finally {
            lock.release()
        }
    }

    suspend fun put(key: K, value: V) {
        cleanup()

        val backingMap = storage.map<K, V>("expiring_shared_data:$mapId:backing_map")
        val expirationMap = storage.map<K, Long>("expiring_shared_data:$mapId:expiration_map")
        val lock = getLock(key)
        try {
            val promise = Promise.promise<Void>()
            backingMap.put(key, value) { result ->
                if (result.succeeded()) {
                    if (expireAfterWriteNanos > 0) {
                        expirationMap.put(key, System.nanoTime()).onSuccess {
                            promise.complete()
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        promise.complete()
                    }
                } else {
                    promise.fail(result.cause())
                }
            }
            promise.future().await()
        } finally {
            lock.release()
        }
    }

    suspend fun cleanup() {
        val promise = Promise.promise<List<K>>()
        val now = System.nanoTime()
        val backingMap = storage.map<K, V>("expiring_shared_data:$mapId:backing_map")
        val expirationMap = storage.map<K, Long>("expiring_shared_data:$mapId:expiration_map")
        expirationMap.entries().onSuccess { entries ->
            val expiredKeys = entries.filter { entry ->
                val expiration = entry.value
                if (expireAfterWriteNanos > 0) {
                    now - expiration > expireAfterWriteNanos
                } else {
                    now - expiration > expireAfterAccessNanos
                }
            }.map { it.key }
            promise.complete(expiredKeys)
        }.onFailure {
            log.error(it) { "Failed to get entries from expiration map" }
            promise.fail(it)
        }

        //remove expired keys
        val expiredKeys = promise.future().await()
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach {
                backingMap.remove(it).await()
                expirationMap.remove(it).await()
            }
            log.trace { "Removed ${expiredKeys.size} expired keys" }
        }
    }

    suspend fun compute(key: K, function: (K, V?) -> V) {
        cleanup()

        val backingMap = storage.map<K, V>("expiring_shared_data:$mapId:backing_map")
        val expirationMap = storage.map<K, Long>("expiring_shared_data:$mapId:expiration_map")
        val lock = getLock(key)
        try {
            val promise = Promise.promise<Void>()
            backingMap.get(key).onSuccess {
                val value = function(key, it)
                backingMap.put(key, value).onSuccess {
                    if (expireAfterWriteNanos > 0 || expireAfterAccessNanos > 0) {
                        expirationMap.put(key, System.nanoTime()).onSuccess {
                            promise.complete()
                        }.onFailure {
                            promise.fail(it)
                        }
                    } else {
                        promise.complete()
                    }
                }.onFailure {
                    promise.fail(it)
                }
            }.onFailure {
                promise.fail(it)
            }
            promise.future().await()
        } finally {
            lock.release()
        }
    }

    class Builder {
        private var expireAfterWriteNanos: Long = -1
        private var expireAfterAccessNanos: Long = -1

        fun expireAfterWrite(duration: Long, unit: TimeUnit) = apply {
            expireAfterWriteNanos = unit.toNanos(duration)
        }

        fun expireAfterAccess(duration: Long, unit: TimeUnit) = apply {
            expireAfterAccessNanos = unit.toNanos(duration)
        }

        fun <K, V> build(mapId: String, storage: CoreStorage): ExpiringSharedData<K, V> {
            return ExpiringSharedData(
                expireAfterWriteNanos,
                expireAfterAccessNanos,
                mapId,
                storage
            )
        }
    }
}
