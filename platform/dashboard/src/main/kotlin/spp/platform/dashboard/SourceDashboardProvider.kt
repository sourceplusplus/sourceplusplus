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
    override fun requiredModules(): Array<String> = arrayOf("spp-platform-storage")
}
