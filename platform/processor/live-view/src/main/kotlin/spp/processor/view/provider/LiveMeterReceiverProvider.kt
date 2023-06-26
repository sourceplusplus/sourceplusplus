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
package spp.processor.view.provider

import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.receiver.meter.provider.MeterReceiverProvider
import spp.processor.view.impl.LiveMeterProcessService
import spp.protocol.instrument.meter.MeterPartition
import spp.protocol.view.rule.RulePartition

/**
 * Replaces the default meter process service with the live meter process service.
 * This is done to allow [LiveMeterProcessService] to process meters with [MeterPartition]s via [RulePartition]s.
 */
class LiveMeterReceiverProvider : MeterReceiverProvider() {

    override fun name(): String = "spp-live-meter-receiver"

    override fun start() {
        val process = manager.find(AnalyzerModule.NAME)
            .provider()
            .getService(IMeterProcessService::class.java) as MeterProcessService
        val analyzerModule = manager.find(AnalyzerModule.NAME).provider()
        analyzerModule.registerServiceImplementation(IMeterProcessService::class.java, LiveMeterProcessService(process))
        super.start()
    }
}
