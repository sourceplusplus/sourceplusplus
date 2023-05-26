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
package spp.processor.insight.provider

import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListenerFactory
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.processor.insight.InsightProcessor

class LiveInsightModule : ModuleDefine("spp-live-insight") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class LiveInsightProvider : ModuleProvider() {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInsightProvider::class.java)
    }

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = LiveInsightModule::class.java
    override fun newConfigCreator(): ConfigCreator<out ModuleConfig>? = null
    override fun prepare() = Unit

    override fun start() {
        log.info("Starting LiveInsightProvider")

        //add inspectors
        val segmentParserService = manager.find(AnalyzerModule.NAME)
            .provider().getService(ISegmentParserService::class.java) as SegmentParserServiceImpl
        val listenerManager = Reflect.on(segmentParserService).get<SegmentParserListenerManager>("listenerManager")
        listenerManager.spanListenerFactories.addAll(InsightProcessor.moderators.filterIsInstance<AnalysisListenerFactory>())

        InsightProcessor.bootProcessor(manager)
        log.info("LiveInsightProvider started")
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> {
        return arrayOf(
            CoreModule.NAME,
            AnalyzerModule.NAME,
            StorageModule.NAME,
            "spp-platform-storage"
        )
    }
}
