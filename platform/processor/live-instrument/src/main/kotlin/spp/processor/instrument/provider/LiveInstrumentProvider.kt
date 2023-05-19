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
package spp.processor.instrument.provider

import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.log.analyzer.provider.log.ILogAnalyzerService
import org.apache.skywalking.oap.log.analyzer.provider.log.LogAnalyzerServiceImpl
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.processor.instrument.InstrumentProcessor
import spp.processor.instrument.impl.LiveBreakpointAnalyzer
import spp.processor.instrument.impl.LiveInstrumentTagAdder
import spp.processor.instrument.impl.LiveLogAnalyzer

class LiveInstrumentModule : ModuleDefine("spp-live-instrument") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class LiveInstrumentProvider : ModuleProvider() {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentProvider::class.java)
    }

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = LiveInstrumentModule::class.java
    override fun newConfigCreator(): ConfigCreator<out ModuleConfig>? = null
    override fun prepare() = Unit

    override fun start() {
        log.info("Starting spp-live-instrument")

        //gather live breakpoints
        val segmentParserService = manager.find(AnalyzerModule.NAME)
            .provider().getService(ISegmentParserService::class.java) as SegmentParserServiceImpl
        val listenerManager = Reflect.on(segmentParserService).get<SegmentParserListenerManager>("listenerManager")
        val spanListenerFactories = Reflect.on(listenerManager).get<MutableList<Any>>("spanListenerFactories")
        spanListenerFactories.add(0, LiveInstrumentTagAdder())
        spanListenerFactories.add(LiveBreakpointAnalyzer())

        //gather live logs
        val logParserService = manager.find(LogAnalyzerModule.NAME)
            .provider().getService(ILogAnalyzerService::class.java) as LogAnalyzerServiceImpl
        logParserService.addListenerFactory(LiveLogAnalyzer())

        InstrumentProcessor.bootProcessor(manager)
        log.info("spp-live-instrument started")
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> = arrayOf(
        CoreModule.NAME,
        AnalyzerModule.NAME,
        StorageModule.NAME,
        LogAnalyzerModule.NAME,
        "spp-platform-storage"
    )
}
