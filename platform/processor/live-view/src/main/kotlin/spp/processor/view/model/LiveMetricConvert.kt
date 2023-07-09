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
package spp.processor.view.model

import mu.KotlinLogging
import org.apache.skywalking.oap.meter.analyzer.Analyzer
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.meter.analyzer.MetricRuleConfig
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.joor.Reflect

class LiveMetricConvert(
    val config: LiveMeterConfig,
    val service: MeterSystem,
    passRule: MetricRuleConfig = NOP_RULE
) : MetricConvert(passRule, service) {

    companion object {
        val NOP_RULE = LiveMeterConfig().apply {
            metricPrefix = "nop"
            metricsRules = emptyList()
        }
        private val log = KotlinLogging.logger {}
    }

    private var setupMetricIds = mutableSetOf<String>()

    @Synchronized
    fun addRule(meterName: String) {
        if (setupMetricIds.contains(meterName)) return
        setupMetricIds.add(meterName)

        val rules = config.getLiveMetricsRules()
        if (rules.size != 1) {
            log.error("Only support one rule for now, but got ${rules.size}")
            throw IllegalArgumentException("Only support one rule for now, but got ${rules.size}")
        }

        val sppAnalyzers = Reflect.on(this).get<MutableList<Analyzer>>("analyzers")
        for (analyzer in sppAnalyzers) {
            val samples = Reflect.on(analyzer).get<List<String>>("samples")
            if (samples.contains(meterName)) {
                log.trace("Metric $meterName already exists, skip")
                return
            }
        }

        val partitionValue = rules.first().partitions.firstNotNullOf {
            Regex(
                it.replace.replace("\$partition\$", "(.*)")
            ).find(meterName)?.groupValues?.getOrNull(1)
        }

        val meterConfig = MeterConfig()
        meterConfig.metricPrefix = "spp"
        meterConfig.metricsRules = listOf(
            MeterConfig.Rule().apply {
                name = rules.first().name + "_$partitionValue"
                exp = rules.first().partitions.fold(rules.first().exp) { acc, partition ->
                    acc.replace(partition.find, partition.replace.replace("\$partition\$", partitionValue))
                }
            }
        )

        val newConvert = MetricConvert(meterConfig, service)
        val newAnalyzer = Reflect.on(newConvert).get<List<Analyzer>>("analyzers").first()
        sppAnalyzers.add(newAnalyzer)
    }
}
