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
package spp.platform.core

import io.vertx.core.DeploymentOptions
import io.vertx.kotlin.coroutines.await
import io.vertx.spi.cluster.redis.RedisClusterManager
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import spp.platform.common.ClusterConnection
import spp.protocol.marshall.LocalMessageCodec

class SourceCoreModule : ModuleDefine("spp-platform-core") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class SourceCoreProvider : ModuleProvider() {
    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = SourceCoreModule::class.java
    override fun createConfigBeanIfAbsent(): ModuleConfig? = null
    override fun prepare() = Unit

    override fun start() {
        RedisClusterManager::class.simpleName //todo: won't need when they make public artifact
        val vertx = ClusterConnection.getVertx()
        vertx.eventBus().registerDefaultCodec(ArrayList::class.java, LocalMessageCodec())
        runBlocking {
            vertx.deployVerticle(SourcePlatform(), DeploymentOptions().setConfig(ClusterConnection.config)).await()
        }
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> = emptyArray()
}
