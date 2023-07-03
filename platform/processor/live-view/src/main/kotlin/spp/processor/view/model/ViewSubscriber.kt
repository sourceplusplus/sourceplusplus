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

import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonObject
import spp.protocol.view.LiveView

data class ViewSubscriber(
    val subscription: LiveView,
    val subscriberId: String,
    var lastUpdated: Long,
    var waitingEvents: MutableMap<Long, MutableList<JsonObject>>,
    val consumer: MessageConsumer<JsonObject>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewSubscriber) return false
        if (subscription != other.subscription) return false
        if (subscriberId != other.subscriberId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = subscription.hashCode()
        result = 31 * result + subscriberId.hashCode()
        return result
    }
}
