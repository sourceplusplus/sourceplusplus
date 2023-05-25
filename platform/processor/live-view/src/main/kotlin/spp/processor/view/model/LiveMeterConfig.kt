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

import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import spp.protocol.view.rule.RulePartition

class LiveMeterConfig : MeterConfig() {

    fun getLiveMetricsRules(): List<Rule> {
        return super.getMetricsRules().mapNotNull { it as? Rule }
    }

    fun hasPartitions(): Boolean {
        return getLiveMetricsRules().any { it.partitions.isNotEmpty() }
    }

    class Rule : MeterConfig.Rule() {
        var partitions: List<RulePartition> = emptyList()
    }
}
