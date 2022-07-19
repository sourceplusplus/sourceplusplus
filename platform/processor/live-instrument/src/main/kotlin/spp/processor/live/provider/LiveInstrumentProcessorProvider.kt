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
package spp.processor.live.provider

import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.log.analyzer.provider.log.ILogAnalyzerService
import org.apache.skywalking.oap.log.analyzer.provider.log.LogAnalyzerServiceImpl
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.query.TraceQueryService
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
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

        val traceQueryService = manager.find(CoreModule.NAME)
            .provider().getService(TraceQueryService::class.java) as TraceQueryService
        val liveInstrumentAnalysis = LiveInstrumentAnalysis(traceQueryService)

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

        InstrumentProcessor.bootProcessor(manager)
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> {
        return arrayOf(
            CoreModule.NAME,
            AnalyzerModule.NAME,
            StorageModule.NAME,
            LogAnalyzerModule.NAME,
        )
    }
}
