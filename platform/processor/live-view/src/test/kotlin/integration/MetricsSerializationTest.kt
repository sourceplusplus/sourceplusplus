/*
 * Source++, the open-source live coding platform.
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
package integration

import integration.testData.EndpointCpmMetrics
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.processor.live.impl.view.model.ClusterMetrics
import java.util.*

class MetricsSerializationTest : PlatformIntegrationTest() {

    @Test
    fun `serialize and deserialize ClusterMetrics`(): Unit = runBlocking {
        val cpmMetrics = EndpointCpmMetrics()
        cpmMetrics.entityId = "entityId1"
        cpmMetrics.serviceId = "serviceId1"
        cpmMetrics.value = 10
        cpmMetrics.total = 100
        cpmMetrics.timeBucket = 1000
        val clusterMetrics = ClusterMetrics(cpmMetrics)

        val testMap = vertx.sharedData().getClusterWideMap<String, ClusterMetrics>(
            UUID.randomUUID().toString()
        ).await()
        testMap.put("testKey", clusterMetrics).await()

        val result = testMap.remove("testKey").await()
        assertEquals(cpmMetrics, result.metrics)
    }
}
