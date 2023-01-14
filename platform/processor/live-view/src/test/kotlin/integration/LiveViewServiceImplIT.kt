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
package integration

import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig

class LiveViewServiceImplIT : PlatformIntegrationTest() {

    @BeforeEach
    fun reset(): Unit = runBlocking {
        viewService.clearLiveViews().await()
    }

    @Test
    fun `test addLiveView`(): Unit = runBlocking {
        val subscription = LiveView(
            entityIds = mutableSetOf("test-id"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric")
            )
        )
        val subscriptionId = viewService.addLiveView(subscription).await().subscriptionId!!

        val subscriptions = viewService.getLiveViews().await()
        assertEquals(1, subscriptions.size)
        assertEquals(subscriptionId, subscriptions[0].subscriptionId)
        assertEquals(subscription.copy(subscriptionId = subscriptionId), subscriptions[0])
    }

    @Test
    fun `test updateLiveView add entity id`(): Unit = runBlocking {
        val subscription = LiveView(
            entityIds = mutableSetOf("test-id-1"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric")
            )
        )
        val subscriptionId = viewService.addLiveView(subscription).await().subscriptionId!!

        val updatedSubscription = subscription.copy(entityIds = mutableSetOf("test-id-1", "test-id-2"))
        viewService.updateLiveView(subscriptionId, updatedSubscription).await()

        val subscriptions = viewService.getLiveViews().await()
        assertEquals(1, subscriptions.size)
        assertEquals(subscriptionId, subscriptions[0].subscriptionId)
        assertEquals(
            updatedSubscription.copy(
                subscriptionId = subscriptionId,
                entityIds = mutableSetOf("test-id-1", "test-id-2")
            ), subscriptions[0]
        )

        val subscriptionStats = managementService.getStats().await().getJsonObject("subscriptions")
        val endpointLogs = subscriptionStats.getJsonObject("test-metric")
        assertEquals(1, endpointLogs.getInteger("test-id-1"))
        assertEquals(1, endpointLogs.getInteger("test-id-2"))
    }

    @Test
    fun `test updateLiveView replace entity id`(): Unit = runBlocking {
        val subscription = LiveView(
            entityIds = mutableSetOf("test-id-1"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric")
            )
        )
        val subscriptionId = viewService.addLiveView(subscription).await().subscriptionId!!

        val updatedSubscription = subscription.copy(entityIds = mutableSetOf("test-id-2"))
        viewService.updateLiveView(subscriptionId, updatedSubscription).await()

        val liveView = viewService.getLiveView(subscriptionId).await()
        assertEquals(subscriptionId, liveView.subscriptionId)
        assertEquals(
            updatedSubscription.copy(
                subscriptionId = subscriptionId,
                entityIds = mutableSetOf("test-id-2")
            ), liveView
        )

        val subscriptionStats = managementService.getStats().await().getJsonObject("subscriptions")
        val endpointLogs = subscriptionStats.getJsonObject("test-metric")
        assertNull(endpointLogs.getInteger("test-id-1"))
        assertEquals(1, endpointLogs.getInteger("test-id-2"))
    }

    @Test
    fun `test removeLiveView`(): Unit = runBlocking {
        val subscription = LiveView(
            entityIds = mutableSetOf("test-id"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric")
            )
        )
        val subscriptionId = viewService.addLiveView(subscription).await().subscriptionId!!

        val removedSubscription = viewService.removeLiveView(subscriptionId).await()
        assertEquals(subscriptionId, removedSubscription.subscriptionId)
        assertEquals(subscription.copy(subscriptionId = subscriptionId), removedSubscription)

        val subscriptions = viewService.getLiveViews().await()
        assertEquals(0, subscriptions.size)
    }

    @Test
    fun `test getLiveView`(): Unit = runBlocking {
        val subscription = LiveView(
            entityIds = mutableSetOf("test-id"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric")
            )
        )
        val subscriptionId = viewService.addLiveView(subscription).await().subscriptionId!!

        val retrievedSubscription = viewService.getLiveView(subscriptionId).await()
        assertEquals(subscriptionId, retrievedSubscription.subscriptionId)
        assertEquals(subscription.copy(subscriptionId = subscriptionId), retrievedSubscription)
    }

    @Test
    fun `test getLiveViews`(): Unit = runBlocking {
        val subscription1 = LiveView(
            entityIds = mutableSetOf("test-id-1"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric-1")
            )
        )
        val subscriptionId1 = viewService.addLiveView(subscription1).await().subscriptionId!!

        val subscription2 = LiveView(
            entityIds = mutableSetOf("test-id-2"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric-2")
            )
        )
        val subscriptionId2 = viewService.addLiveView(subscription2).await().subscriptionId!!

        val subscriptions = viewService.getLiveViews().await()
        assertEquals(2, subscriptions.size)
        assertEquals(subscriptionId1, subscriptions[0].subscriptionId)
        assertEquals(subscription1.copy(subscriptionId = subscriptionId1), subscriptions[0])
        assertEquals(subscriptionId2, subscriptions[1].subscriptionId)
        assertEquals(subscription2.copy(subscriptionId = subscriptionId2), subscriptions[1])
    }

    @Test
    fun `test clearLiveViews`(): Unit = runBlocking {
        val subscription1 = LiveView(
            entityIds = mutableSetOf("test-id-1"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric-1")
            )
        )
        val subId1 = viewService.addLiveView(subscription1).await().subscriptionId!!

        val subscription2 = LiveView(
            entityIds = mutableSetOf("test-id-2"),
            viewConfig = LiveViewConfig(
                "test",
                listOf("test-metric-2")
            )
        )
        val subId2 = viewService.addLiveView(subscription2).await().subscriptionId!!

        val clearedSubscriptions = viewService.clearLiveViews().await()
        assertEquals(2, clearedSubscriptions.size)
        assertEquals(
            subscription1.copy(subscriptionId = subId1),
            clearedSubscriptions.find { it.subscriptionId == subId1 }
        )
        assertEquals(
            subscription2.copy(subscriptionId = subId2),
            clearedSubscriptions.find { it.subscriptionId == subId2 }
        )

        val subscriptions = viewService.getLiveViews().await()
        assertEquals(0, subscriptions.size)
    }
}
