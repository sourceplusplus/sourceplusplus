package spp.processor.live.provider

import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.log.analyzer.provider.log.ILogAnalyzerService
import org.apache.skywalking.oap.log.analyzer.provider.log.LogAnalyzerServiceImpl
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.core.storage.query.ILogQueryDAO
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO
import org.slf4j.LoggerFactory
import spp.processor.InstrumentProcessor
import spp.processor.live.impl.instrument.LiveInstrumentAnalysis

class LiveInstrumentModule : ModuleDefine("spp-live-instrument") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class LiveInstrumentProcessorProvider : ModuleProvider() {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentProcessorProvider::class.java)
    }

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = LiveInstrumentModule::class.java
    override fun createConfigBeanIfAbsent(): ModuleConfig? = null
    override fun prepare() = Unit

    override fun start() {
        log.info("Starting LiveInstrumentProcessorProvider")
        InstrumentProcessor.module = manager

        //todo: indexes

        val elasticSearch = manager.find(StorageModule.NAME).provider()
            .getService(ILogQueryDAO::class.java) as EsDAO
        val liveInstrumentAnalysis = LiveInstrumentAnalysis(elasticSearch)

        //gather live breakpoints
        val segmentParserService = manager.find(AnalyzerModule.NAME)
            .provider().getService(ISegmentParserService::class.java) as SegmentParserServiceImpl
        val listenerManagerField = segmentParserService.javaClass.getDeclaredField("listenerManager")
        listenerManagerField.trySetAccessible()
        val listenerManager = listenerManagerField.get(segmentParserService) as SegmentParserListenerManager
        listenerManager.add(liveInstrumentAnalysis)

        //gather live logs
        val logParserService = manager.find(LogAnalyzerModule.NAME)
            .provider().getService(ILogAnalyzerService::class.java) as LogAnalyzerServiceImpl
        logParserService.addListenerFactory(liveInstrumentAnalysis)
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> {
        return arrayOf(
            StorageModule.NAME,
            SharingServerModule.NAME
        )
    }
}
