/*
 * Source++, the continuous feedback platform for developers.
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
package spp.platform.dashboard

import io.vertx.core.DeploymentOptions
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import kotlin.system.exitProcess

class SourceDashboardModule : ModuleDefine("spp-live-dashboard") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class SourceDashboardProvider : ModuleProvider() {
    private val log: Logger = LoggerFactory.getLogger(SourceDashboardProvider::class.java)

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = SourceDashboardModule::class.java
    override fun createConfigBeanIfAbsent(): ModuleConfig? = null
    override fun prepare() = Unit

    override fun start() {
        try {
            log.info("Starting spp-dashboard")
            ClusterConnection.getVertx()
                .deployVerticle(SourceDashboard(), DeploymentOptions().setConfig(ClusterConnection.config))
        } catch (e: Exception) {
            log.error("Failed to start spp-dashboard.", e)
            exitProcess(-1)
        }
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> = arrayOf("spp-platform-storage", "spp-platform-core")
}
