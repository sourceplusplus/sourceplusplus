package spp.processor.logging.provider

import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import org.slf4j.LoggerFactory
import spp.processor.LogSummaryProcessor

class LoggingModule : ModuleDefine("spp-logging") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class LoggingProcessorProvider : ModuleProvider() {

    companion object {
        private val log = LoggerFactory.getLogger(LoggingProcessorProvider::class.java)
    }

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = LoggingModule::class.java
    override fun createConfigBeanIfAbsent(): ModuleConfig? = null
    override fun prepare() = Unit

    override fun start() {
        log.info("Starting LoggingProcessorProvider")
        LogSummaryProcessor.module = manager
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> {
        return arrayOf(
            StorageModule.NAME,
            SharingServerModule.NAME
        )
    }
}
