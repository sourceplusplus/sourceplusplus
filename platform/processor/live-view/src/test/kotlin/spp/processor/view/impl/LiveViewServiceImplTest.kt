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

import org.apache.skywalking.oap.meter.analyzer.Analyzer
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.meter.analyzer.dsl.Expression
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.joor.Reflect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import spp.protocol.view.rule.RulePartition
import spp.protocol.view.rule.ViewRule

class LiveViewServiceImplTest {

    @Test
    fun createRule() {
        val viewService = LiveViewServiceImpl()
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        viewService.saveRule(
            ViewRule(
                name = "build_test1",
                exp = "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)"
            )
        )

        assertEquals(1, convertList.size)
        val analyzers = Reflect.on(convertList.first()).get<List<Analyzer>>("analyzers")
        assertEquals(1, analyzers.size)
        val analyzer = analyzers.first()
        assertEquals("spp_build_test1", Reflect.on(analyzer).get("metricName"))
        val samples = Reflect.on(analyzer).get<List<String>>("samples")
        assertEquals(1, samples.size)
        assertEquals("test_count1", samples.first())
        val expression = Reflect.on(analyzer).get<Expression>("expression")
        assertEquals(
            "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)",
            Reflect.on(expression).get("literal")
        )
    }

    @Test
    fun `two unique rules`() {
        val viewService = LiveViewServiceImpl()
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        viewService.saveRule(
            ViewRule(
                name = "build_test1",
                exp = "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)"
            )
        )
        viewService.saveRule(
            ViewRule(
                name = "build_test2",
                exp = "test_count2.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)"
            )
        )

        assertEquals(2, convertList.size)

        val analyzer1 = Reflect.on(convertList[0]).get<List<Analyzer>>("analyzers").first()
        assertEquals("spp_build_test1", Reflect.on(analyzer1).get("metricName"))
        val samples1 = Reflect.on(analyzer1).get<List<String>>("samples")
        assertEquals(1, samples1.size)
        assertEquals("test_count1", samples1.first())
        val expression1 = Reflect.on(analyzer1).get<Expression>("expression")
        assertEquals(
            "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)",
            Reflect.on(expression1).get("literal")
        )

        val analyzer2 = Reflect.on(convertList[1]).get<List<Analyzer>>("analyzers").first()
        assertEquals("spp_build_test2", Reflect.on(analyzer2).get("metricName"))
        val samples2 = Reflect.on(analyzer2).get<List<String>>("samples")
        assertEquals(1, samples2.size)
        assertEquals("test_count2", samples2.first())
        val expression2 = Reflect.on(analyzer2).get<Expression>("expression")
        assertEquals(
            "test_count2.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)",
            Reflect.on(expression2).get("literal")
        )
    }

    @Test
    fun `try to save duplicate rule`() {
        val viewService = LiveViewServiceImpl()
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        val rule = ViewRule(
            name = "build_test1",
            exp = "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)"
        )
        viewService.saveRule(rule)
        viewService.saveRule(rule)

        assertEquals(1, convertList.size)
        val analyzers = Reflect.on(convertList.first()).get<List<Analyzer>>("analyzers")
        assertEquals(1, analyzers.size)
        val analyzer = analyzers.first()
        assertEquals("spp_build_test1", Reflect.on(analyzer).get("metricName"))
        val samples = Reflect.on(analyzer).get<List<String>>("samples")
        assertEquals(1, samples.size)
        assertEquals("test_count1", samples.first())
        val expression = Reflect.on(analyzer).get<Expression>("expression")
        assertEquals(
            "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)",
            Reflect.on(expression).get("literal")
        )
    }

    @Test
    fun deleteRule() {
        val viewService = LiveViewServiceImpl()
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        //add rule
        val rule = viewService.saveRule(
            ViewRule(
                name = "build_test1",
                exp = "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)"
            )
        ).toCompletionStage().toCompletableFuture().get()
        assertEquals(1, convertList.size)
        val analyzers = Reflect.on(convertList.first()).get<List<Analyzer>>("analyzers")
        assertEquals(1, analyzers.size)

        //delete rule
        val deletedRule = viewService.deleteRule(rule.name).toCompletionStage().toCompletableFuture().get()
        assertEquals(rule.copy(name = "spp_" + rule.name), deletedRule)
        assertEquals(0, convertList.size)
    }

    @Test
    fun deleteRule_partitioned() {
        val viewService = LiveViewServiceImpl()
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        //add partitioned rule
        val rule = viewService.saveRule(
            ViewRule(
                name = "build_test1",
                exp = "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)",
                partitions = listOf(
                    RulePartition(
                        "test_count1",
                        "test_count1_\$partition\$"
                    )
                )
            )
        ).toCompletionStage().toCompletableFuture().get()
        assertEquals(1, convertList.size)
        val analyzers = Reflect.on(convertList.first()).get<MutableList<Analyzer>>("analyzers")
        assertEquals(0, analyzers.size)

        //manually add partition
        val meterConfig = MeterConfig()
        meterConfig.metricPrefix = "spp"
        meterConfig.metricsRules = listOf(
            MeterConfig.Rule().apply {
                name = rule.name + "_test1"
                exp = rule.partitions.fold(rule.exp) { acc, partition ->
                    acc.replace(partition.find, partition.replace.replace("\$partition\$", "test1"))
                }
            }
        )
        val newConvert = MetricConvert(meterConfig, viewService.meterSystem)
        val newAnalyzer = Reflect.on(newConvert).get<List<Analyzer>>("analyzers").first()
        analyzers.add(newAnalyzer)

        //delete rule
        val deletedRule = viewService.deleteRule(rule.name).toCompletionStage().toCompletableFuture().get()
        assertEquals(rule.copy(name = "spp_" + rule.name), deletedRule)
        assertEquals(0, convertList.size)
    }
}
