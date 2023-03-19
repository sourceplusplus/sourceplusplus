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
package spp.processor.live.impl.insight

import com.codahale.metrics.*
import com.codahale.metrics.Timer
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.apm.network.language.agent.v3.Label
import org.apache.skywalking.apm.network.language.agent.v3.MeterData
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.slf4j.LoggerFactory
import spp.platform.common.FeedbackProcessor
import spp.platform.storage.SourceStorage
import spp.processor.InsightProcessor
import spp.processor.live.impl.moderate.model.UniqueMeterName
import spp.protocol.view.rule.LiveViewRule
import java.util.*
import java.util.concurrent.TimeUnit

object LiveMetricProcessor {

    private val log = LoggerFactory.getLogger(LiveMetricProcessor::class.java)
    private val processService by lazy {
        FeedbackProcessor.module!!.find(AnalyzerModule.NAME)
            .provider().getService(IMeterProcessService::class.java)
    }

    private val metricLock = Any()
    private val registry = MetricRegistry()
    private val metricMap = mutableMapOf<UniqueMeterName, Metric>()

    init {
        val reporter = Reporter()
        reporter.start(10, TimeUnit.SECONDS)
    }

    fun getCounter(key: UniqueMeterName): Counter {
        val meterName = buildString {
            append(key.type.name.lowercase())
            append("_")
            append(key.methodName!!.replace("[^a-zA-Z0-9]".toRegex(), "_"))
        }
        return getOrCreateCounter(key.copy(meterName = meterName))
    }

    fun getGauge(key: UniqueMeterName): SettableGauge<Long> {
        val meterName = buildString {
            append(key.type.name.lowercase())
            append("_")
            append(key.methodName!!.replace("[^a-zA-Z0-9]".toRegex(), "_"))
        }
        return getOrCreateGauge(key.copy(meterName = meterName))
    }

    private fun getOrCreateGauge(key: UniqueMeterName): SettableGauge<Long> {
        if (metricMap.containsKey(key)) {
            return (metricMap[key] as SettableGauge<Long>)
        }

        synchronized(metricLock) {
            if (metricMap.containsKey(key)) {
                return metricMap[key] as SettableGauge<Long>
            }

            val gauge: SettableGauge<Long> = registry.gauge(key.toString())
            metricMap[key] = gauge

            runBlocking {
                SourceStorage.put("spp_${key.type.name.lowercase()}_${key.meterName}_gauge", key.methodName!!)
            }
            log.info("Saving rule: ${key.type.name.lowercase()}_${key.meterName}_gauge")
            InsightProcessor.viewService!!.saveRule( //todo: saveRuleIfAbsent
                LiveViewRule(
                    name = "${key.type.name.lowercase()}_${key.meterName}_gauge",
                    exp = buildString {
                        append("(")
                        append(key.type.name.lowercase()).append("_").append(key.meterName)
                        append(".sum(['service'])")
                        append(")")
                        append(".service(['service'], Layer.GENERAL)")
                    }
                )
            ).onFailure {
                if (it.message?.endsWith("already exists") == true) {
                    log.info(it.message) // expected
                } else {
                    log.error("Failed to save rule", it)
                }
            }

            return gauge
        }
    }

    private fun getOrCreateCounter(key: UniqueMeterName): Counter {
        if (metricMap.containsKey(key)) {
            return metricMap[key] as Counter
        }

        synchronized(metricLock) {
            if (metricMap.containsKey(key)) {
                return metricMap[key] as Counter
            }

            val counter = registry.counter(key.toString())
            metricMap[key] = counter

            runBlocking {
                SourceStorage.put("spp_${key.type.name.lowercase()}_${key.meterName}_count", key.methodName!!)
            }
            log.info("Saving rule: ${key.type.name.lowercase()}_${key.meterName}_count")
            InsightProcessor.viewService!!.saveRule( //todo: saveRuleIfAbsent
                LiveViewRule(
                    name = "${key.type.name.lowercase()}_${key.meterName}_count",
                    exp = buildString {
                        append("(")
                        append(key.type.name.lowercase()).append("_").append(key.meterName)
                        append(".sum(['service', 'method_call'])")
                        append(")")
                        append(".service(['service'], Layer.GENERAL)")
                    }
                )
            ).onFailure {
                if (it.message?.endsWith("already exists") == true) {
                    log.info(it.message) // expected
                } else {
                    log.error("Failed to save rule", it)
                }
            }

            return counter
        }
    }

    class Reporter : ScheduledReporter(
        registry,
        "live-metric-reporter",
        MetricFilter.ALL,
        TimeUnit.SECONDS,
        TimeUnit.MILLISECONDS
    ) {
        override fun report(
            gauges: SortedMap<String, Gauge<Any>>,
            counters: SortedMap<String, Counter>,
            histograms: SortedMap<String, Histogram>,
            meters: SortedMap<String, Meter>,
            timers: SortedMap<String, Timer>
        ) {
            metricMap.forEach { (key, metric) ->
                if (metric is Counter) {
                    processService.createProcessor().apply {
                        read(
                            MeterData.newBuilder().apply {
                                singleValueBuilder.setValue(metric.count.toDouble())
                                    .setName("${key.type.name.lowercase()}_${key.meterName}")
                                    .addLabels(
                                        Label.newBuilder().setName("method_call")
                                            .setValue(key.tagValues.values.get(0)).build()
                                    )
                                    .build()
                            }.setService("test_service").setServiceInstance("tester")
                                .setTimestamp(System.currentTimeMillis())
                                .build()
                        )
                        process()
                    }
                } else if (metric is Gauge<*>) {
                    val value = metric.value?.toString()?.toDoubleOrNull() ?: return@forEach
                    processService.createProcessor().apply {
                        read(
                            MeterData.newBuilder().apply {
                                singleValueBuilder.setValue(value)
                                    .setName("${key.type.name.lowercase()}_${key.meterName}")
                                    .build()
                            }.setService("test_service").setServiceInstance("tester")
                                .setTimestamp(System.currentTimeMillis())
                                .build()
                        )
                        process()
                    }
                }
            }
        }
    }
}
