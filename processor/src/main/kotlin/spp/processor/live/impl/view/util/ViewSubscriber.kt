package spp.processor.live.impl.view.util

import com.sourceplusplus.protocol.view.LiveViewSubscription
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonObject
import org.apache.skywalking.oap.server.core.exporter.ExportEvent

data class ViewSubscriber(
    val subscription: LiveViewSubscription,
    val subscriberId: String,
    var lastUpdated: Long,
    var waitingEvents: MutableList<ExportEvent>,
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
