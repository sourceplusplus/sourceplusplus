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
package spp.processor.live.impl

import org.apache.skywalking.apm.network.language.agent.v3.MeterData
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessor
import spp.processor.live.model.LiveMetricConvert
import spp.protocol.instrument.LiveMeter

class LiveMeterProcessService(private val delegate: MeterProcessService) : IMeterProcessService by delegate {

    private val existingPartitions = mutableSetOf<String>()

    override fun createProcessor(): MeterProcessor {
        val processor = delegate.createProcessor()
        return object : MeterProcessor(delegate) {
            override fun read(data: MeterData) {
                val meterName = if (data.hasSingleValue()) {
                    LiveMeter.formatMeterName(data.singleValue.name)
                } else if (data.hasHistogram()) {
                    LiveMeter.formatMeterName(data.histogram.name)
                } else null ?: return

                if (existingPartitions.contains(meterName)) {
                    processor.read(data)
                } else {
                    //see if partition is necessary
                    delegate.converts().filterIsInstance<LiveMetricConvert>().filter {
                        it.config.hasPartitions()
                    }.find {
                        it.config.getLiveMetricsRules().any {
                            it.partitions.any {
                                meterName.startsWith(it.replace.replace("_\$partition\$", ""))
                            }
                        }
                    }?.addRule(existingPartitions, meterName)
                    processor.read(data)
                }
            }

            override fun process() = processor.process()
        }
    }
}
