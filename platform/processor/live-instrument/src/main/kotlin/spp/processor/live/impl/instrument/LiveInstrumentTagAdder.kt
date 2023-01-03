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
package spp.processor.live.impl.instrument

import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.oap.server.analyzer.provider.AnalyzerModuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListener
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.AnalysisListenerFactory
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.listener.SegmentListener
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.joor.Reflect
import spp.protocol.instrument.LiveInstrumentType

class LiveInstrumentTagAdder : SegmentListener, AnalysisListenerFactory {

    override fun parseSegment(segmentObject: SegmentObject) {
        segmentObject.spansList.forEach { span ->
            var breakpointId: String? = null
            span.tagsList.forEach {
                if (it.key.startsWith(LiveBreakpointAnalyzer.BREAKPOINT)) {
                    breakpointId = it.key.substring(LiveBreakpointAnalyzer.BREAKPOINT.length)
                }
            }

            if (breakpointId != null) {
                val tagsList = span.tagsList.toMutableList()
                tagsList.add(
                    KeyStringValuePair.newBuilder().setKey("spp.instrument_id")
                        .setValue(breakpointId).build()
                )
                tagsList.add(
                    KeyStringValuePair.newBuilder().setKey("spp.instrument_type")
                        .setValue(LiveInstrumentType.BREAKPOINT.ordinal.toString()).build()
                )
                Reflect.on(span).set("tags_", tagsList)
            }
        }
    }

    override fun build() = Unit
    override fun containsPoint(point: AnalysisListener.Point) = point == AnalysisListener.Point.Segment
    override fun create(moduleManager: ModuleManager?, config: AnalyzerModuleConfig?) = LiveInstrumentTagAdder()
}
