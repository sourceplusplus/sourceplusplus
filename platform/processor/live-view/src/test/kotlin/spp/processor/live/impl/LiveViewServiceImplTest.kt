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
package spp.processor.live.impl

import org.apache.skywalking.oap.meter.analyzer.Analyzer
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.meter.analyzer.dsl.Expression
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.joor.Reflect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import spp.protocol.view.rule.LiveViewRule
import spp.protocol.view.rule.LiveViewRuleset

class LiveViewServiceImplTest {

    @Test
    fun createRuleset() {
        val viewService = LiveViewServiceImpl()
        viewService.skywalkingVersion = "9+"
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        viewService.saveRuleset(
            LiveViewRuleset(
                expSuffix = "service(['service'], Layer.GENERAL)",
                metricPrefix = "meter",
                metricsRules = listOf(
                    LiveViewRule(
                        name = "user_login_count",
                        exp = "user_login.sum(['service']).downsampling(SUM)"
                    )
                )
            )
        )

        assertEquals(1, convertList.size)
        val analyzers = Reflect.on(convertList.first()).get<List<Analyzer>>("analyzers")
        assertEquals(1, analyzers.size)
        val analyzer = analyzers.first()
        assertEquals("meter_user_login_count", Reflect.on(analyzer).get("metricName"))
        val samples = Reflect.on(analyzer).get<List<String>>("samples")
        assertEquals(1, samples.size)
        assertEquals("user_login", samples.first())
        val expression = Reflect.on(analyzer).get<Expression>("expression")
        assertEquals(
            "(user_login.sum(['service']).downsampling(SUM)).service(['service'], Layer.GENERAL)",
            Reflect.on(expression).get("literal")
        )
    }

    @Test
    fun deleteRuleset() {
        val viewService = LiveViewServiceImpl()
        viewService.skywalkingVersion = "9+"
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        //add ruleset
        val ruleset = viewService.saveRuleset(
            LiveViewRuleset(
                expSuffix = "service(['service'], Layer.GENERAL)",
                metricPrefix = "meter",
                metricsRules = listOf(
                    LiveViewRule(
                        name = "user_login_count",
                        exp = "user_login.sum(['service']).downsampling(SUM)"
                    )
                )
            )
        ).toCompletionStage().toCompletableFuture().get()
        assertEquals(1, convertList.size)

        //delete ruleset
        viewService.deleteRuleset(ruleset.id!!)
        assertEquals(0, convertList.size)
    }

    @Test
    fun createRule() {
        val viewService = LiveViewServiceImpl()
        viewService.skywalkingVersion = "9+"
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        viewService.saveRule(
            LiveViewRule(
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
    fun deleteRule() {
        val viewService = LiveViewServiceImpl()
        viewService.skywalkingVersion = "9+"
        viewService.meterSystem = Mockito.mock(MeterSystem::class.java)

        val convertList = mutableListOf<MetricConvert>()
        viewService.meterProcessService = Mockito.mock(MeterProcessService::class.java).apply {
            Mockito.`when`(converts()).thenReturn(convertList)
        }

        //add rule
        val rule = viewService.saveRule(
            LiveViewRule(
                name = "build_test1",
                exp = "test_count1.tagEqual(\"k1\", \"v1\").service([\"service\"], Layer.GENERAL)"
            )
        ).toCompletionStage().toCompletableFuture().get()
        assertEquals(1, convertList.size)

        //delete rule
        val deletedRule = viewService.deleteRule(rule.name).toCompletionStage().toCompletableFuture().get()
        assertEquals(rule.copy(name = "spp_" + rule.name), deletedRule)
        assertEquals(0, convertList.size)
    }
}
