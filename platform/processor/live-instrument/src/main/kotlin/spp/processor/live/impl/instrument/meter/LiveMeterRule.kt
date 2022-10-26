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
package spp.processor.live.impl.instrument.meter

import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.core.version.Version
import spp.processor.live.impl.LiveInstrumentServiceImpl
import spp.protocol.instrument.LiveMeter
import spp.protocol.instrument.meter.MeterType

class LiveMeterRule(liveMeter: LiveMeter) : MeterConfig.Rule() {

    init {
        when (liveMeter.meterType) {
            MeterType.COUNT -> {
                val idVariable = liveMeter.toMetricIdWithoutPrefix()
                name = idVariable
                exp = if (Version.CURRENT.buildVersion.startsWith("8")) {
                    buildString {
                        append("(")
                        append(idVariable)
                        append(".sum(['service', 'instance'])")
                        append(".downsampling(SUM)")
                        append(")")
                        append(".instance(['service'], ['instance'])")
                    }
                } else if (Version.CURRENT.buildVersion.startsWith("9")) {
                    buildString {
                        append("(")
                        append(idVariable)
                        append(".sum(['service', 'instance'])")
                        append(".downsampling(SUM)")
                        append(")")
                        append(".instance(['service'], ['instance'], Layer.GENERAL)")
                    }
                } else {
                    error("Unsupported version: ${Version.CURRENT.buildVersion}")
                }
            }

            MeterType.GAUGE -> {
                val idVariable = liveMeter.toMetricIdWithoutPrefix()
                name = idVariable
                exp = if (Version.CURRENT.buildVersion.startsWith("8")) {
                    buildString {
                        append("(")
                        append(idVariable)
                        append(".downsampling(LATEST)")
                        append(")")
                        append(".instance(['service'], ['instance'])")
                    }
                } else if (Version.CURRENT.buildVersion.startsWith("9")) {
                    buildString {
                        append("(")
                        append(idVariable)
                        append(".downsampling(LATEST)")
                        append(")")
                        append(".instance(['service'], ['instance'], Layer.GENERAL)")
                    }
                } else {
                    error("Unsupported version: ${Version.CURRENT.buildVersion}")
                }
            }

            MeterType.HISTOGRAM -> {
                val idVariable = liveMeter.toMetricIdWithoutPrefix()
                name = idVariable
                exp = if (Version.CURRENT.buildVersion.startsWith("8")) {
                    buildString {
                        append("(")
                        append(idVariable)
                        append(".sum(['le', 'service', 'instance'])")
                        append(".increase('PT5M')")
                        append(".histogram()")
                        append(".histogram_percentile([50,70,90,99])")
                        append(")")
                        append(".instance(['service'], ['instance'])")
                    }
                } else if (Version.CURRENT.buildVersion.startsWith("9")) {
                    buildString {
                        append("(")
                        append(idVariable)
                        append(".sum(['le', 'service', 'instance'])")
                        append(".increase('PT5M')")
                        append(".histogram()")
                        append(".histogram_percentile([50,70,90,99])")
                        append(")")
                        append(".instance(['service'], ['instance'], Layer.GENERAL)")
                    }
                } else {
                    error("Unsupported version: ${Version.CURRENT.buildVersion}")
                }
            }

            else -> error("Unsupported meter type: ${liveMeter.meterType}")
        }
    }

    companion object {
        fun toMeterConfig(liveMeter: LiveMeter): MeterConfig? {
            if (!liveMeter.metricValue.valueType.isAlwaysNumeric()) {
                //non-numeric gauges are currently handled via live logs.
                // SW may support this via meters in the future
                return null
            }

            val meterConfig = MeterConfig()
            meterConfig.metricPrefix = LiveInstrumentServiceImpl.METRIC_PREFIX
            meterConfig.metricsRules = mutableListOf<MeterConfig.Rule>(LiveMeterRule(liveMeter))
            return meterConfig
        }
    }
}
