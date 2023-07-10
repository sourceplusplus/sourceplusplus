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
package spp.processor.view.impl

import org.apache.skywalking.apm.network.language.agent.v3.MeterData
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessor
import org.apache.skywalking.oap.server.library.module.ModuleManager
import spp.processor.view.model.LiveMetricConvert
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.meter.MeterPartition
import spp.protocol.view.rule.RulePartition

/**
 * Replaces the default meter process service to allows for processing
 * meters with [MeterPartition]s via [RulePartition]s.
 */
class LiveMeterProcessService(
    private val delegate: MeterProcessService,
    manager: ModuleManager,
) : MeterProcessService(manager) {

    override fun start(configs: MutableList<MeterConfig>?) {
        delegate.start(configs)
    }

    override fun createProcessor(): MeterProcessor {
        val processor = delegate.createProcessor()
        return object : MeterProcessor(delegate) {
            override fun read(data: MeterData) {
                val meterName = if (data.hasSingleValue()) {
                    LiveMeter.formatMeterName(data.singleValue.name)
                } else if (data.hasHistogram()) {
                    LiveMeter.formatMeterName(data.histogram.name)
                } else null
                if (meterName == null) {
                    processor.read(data)
                    return
                }

                //see if partition(s) are necessary
                delegate.converts().filterIsInstance<LiveMetricConvert>().filter {
                    it.config.hasPartitions()
                }.filter {
                    it.config.getLiveMetricsRules().any {
                        it.partitions.any {
                            meterName.startsWith(it.replace.replace("_\$partition\$", ""))
                        }
                    }
                }.forEach { it.addRule(meterName) }

                //continue processing
                processor.read(data)
            }

            override fun process() = processor.process()
        }
    }

    override fun converts(): MutableList<MetricConvert> {
        return delegate.converts()
    }
}
