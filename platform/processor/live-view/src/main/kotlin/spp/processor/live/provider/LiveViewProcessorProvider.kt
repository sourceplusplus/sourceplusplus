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
package spp.processor.live.provider

import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.manual.log.LogRecord
import org.apache.skywalking.oap.server.core.analysis.manual.segment.SegmentRecord
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor
import org.apache.skywalking.oap.server.core.exporter.ExportEvent
import org.apache.skywalking.oap.server.core.exporter.LogExportService
import org.apache.skywalking.oap.server.core.exporter.MetricValuesExportService
import org.apache.skywalking.oap.server.core.exporter.TraceExportService
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.processor.ViewProcessor
import spp.processor.ViewProcessor.liveViewService
import spp.processor.live.impl.SPPMetricsStreamProcessor

class LiveViewModule : ModuleDefine("exporter") {
    override fun services(): Array<Class<*>> = arrayOf(
        MetricValuesExportService::class.java,
        TraceExportService::class.java,
        LogExportService::class.java
    )
}

class LiveViewProcessorProvider : ModuleProvider() {

    companion object {
        private val log = LoggerFactory.getLogger(LiveViewProcessorProvider::class.java)
    }

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = LiveViewModule::class.java
    override fun newConfigCreator(): ConfigCreator<out ModuleConfig>? = null
    override fun prepare() {
        //todo: should be able to hook into metrics in a smarter way
        val sppMetricsStreamProcessor = SPPMetricsStreamProcessor()
        Reflect.onClass(MetricsStreamProcessor::class.java).set("PROCESSOR", sppMetricsStreamProcessor)

        registerServiceImplementation(MetricValuesExportService::class.java, object : MetricValuesExportService {
            override fun export(event: ExportEvent) {
                if (event.type == ExportEvent.EventType.TOTAL && event.metrics is WithMetadata) {
                    GlobalScope.launch(ClusterConnection.getVertx().dispatcher()) {
                        liveViewService.meterView.export(event.metrics, false)
                    }
                }
            }

            override fun start() = Unit
            override fun isEnabled() = true
        })

        //we don't use trace/log exports but need to register services anyway
        registerServiceImplementation(TraceExportService::class.java, object : TraceExportService {
            override fun export(segmentRecord: SegmentRecord) = Unit
            override fun start() = Unit
            override fun isEnabled() = false
        })
        registerServiceImplementation(LogExportService::class.java, object : LogExportService {
            override fun export(logRecord: LogRecord) = Unit
            override fun start() = Unit
            override fun isEnabled() = false
        })
    }

    override fun start() {
        log.info("Starting spp-live-view")
        ViewProcessor.bootProcessor(manager)
        log.info("spp-live-view started")
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
