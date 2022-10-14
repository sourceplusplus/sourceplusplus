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
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.processor.InstrumentProcessor
import spp.processor.live.impl.instrument.LiveBreakpointAnalyzer
import spp.processor.live.impl.instrument.LiveInstrumentTagAdder
import spp.processor.live.impl.instrument.LiveLogAnalyzer

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

        //gather live breakpoints
        val traceQueryService = manager.find(CoreModule.NAME)
            .provider().getService(TraceQueryService::class.java) as TraceQueryService
        val segmentParserService = manager.find(AnalyzerModule.NAME)
            .provider().getService(ISegmentParserService::class.java) as SegmentParserServiceImpl
        val listenerManager = Reflect.on(segmentParserService).get<SegmentParserListenerManager>("listenerManager")
        val spanListenerFactories = Reflect.on(listenerManager).get<MutableList<Any>>("spanListenerFactories")
        spanListenerFactories.add(0, LiveInstrumentTagAdder())
        spanListenerFactories.add(LiveBreakpointAnalyzer(traceQueryService))

        //gather live logs
        val logParserService = manager.find(LogAnalyzerModule.NAME)
            .provider().getService(ILogAnalyzerService::class.java) as LogAnalyzerServiceImpl
        logParserService.addListenerFactory(LiveLogAnalyzer())

        InstrumentProcessor.bootProcessor(manager)
        log.info("LiveInstrumentProcessorProvider started")
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
