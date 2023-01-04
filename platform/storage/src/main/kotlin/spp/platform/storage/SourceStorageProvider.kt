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

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.platform.common.ClusterConnection.config
import kotlin.system.exitProcess

class SourceStorageModule : ModuleDefine("spp-platform-storage") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class SourceStorageProvider : ModuleProvider() {
    private val log: Logger = LoggerFactory.getLogger(SourceStorageProvider::class.java)

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = SourceStorageModule::class.java
    override fun newConfigCreator(): ConfigCreator<out ModuleConfig>? = null
    override fun prepare() = Unit

    override fun start() {
        try {
            log.info("Starting spp-storage")
            val vertx = ClusterConnection.getVertx()
            runBlocking(vertx.dispatcher()) {
                when (val storageSelector = config.getJsonObject("storage").getString("selector")) {
                    "memory" -> {
                        log.info("Using in-memory storage")
                        SourceStorage.setup(MemoryStorage(vertx))
                    }

                    "redis" -> {
                        log.info("Using Redis storage")
                        val redisStorage = RedisStorage(vertx)
                        redisStorage.init(SourceStorage.getStorageConfig())

                        SourceStorage.setup(redisStorage)
                    }

                    else -> {
                        Class.forName(storageSelector, false, SourceStorage::class.java.classLoader)
                        log.info("Using custom storage: $storageSelector")
                        val storageClass = Class.forName(storageSelector)
                        val customStorage = try {
                            storageClass.getConstructor(Vertx::class.java).newInstance(vertx)
                        } catch (ignore: NoSuchMethodException) {
                            storageClass.getConstructor()
                        } as CoreStorage
                        customStorage.init(SourceStorage.getStorageConfig())

                        SourceStorage.setup(customStorage)
                    }
                }
            }
            log.info("spp-storage started")
        } catch (e: Exception) {
            log.error("Failed to start spp-storage.", e)
            exitProcess(-1)
        }
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> = emptyArray()
}
