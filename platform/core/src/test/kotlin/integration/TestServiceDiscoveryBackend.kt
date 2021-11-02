package integration

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.BridgeEventType
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.spi.ServiceDiscoveryBackend
import java.util.*

class TestServiceDiscoveryBackend : ServiceDiscoveryBackend {

    private lateinit var vertx: Vertx

    override fun init(vertx: Vertx, config: JsonObject?) {
        this.vertx = vertx
    }

    override fun store(record: Record?, resultHandler: Handler<AsyncResult<Record>>?) {
        TODO("Not yet implemented")
    }

    override fun remove(record: Record?, resultHandler: Handler<AsyncResult<Record>>?) {
        TODO("Not yet implemented")
    }

    override fun remove(uuid: String?, resultHandler: Handler<AsyncResult<Record>>?) {
        TODO("Not yet implemented")
    }

    override fun update(record: Record?, resultHandler: Handler<AsyncResult<Void>>?) {
        TODO("Not yet implemented")
    }

    override fun getRecords(resultHandler: Handler<AsyncResult<MutableList<Record>>>) {
        val replyAddress = UUID.randomUUID().toString()
        val consumer = vertx.eventBus().localConsumer<JsonArray>("local.$replyAddress")
        consumer.handler {
            resultHandler.handle(Future.succeededFuture(it.body().map { Record(it as JsonObject) }.toMutableList()))
            consumer.unregister()
        }
        FrameHelper.sendFrame(
            BridgeEventType.SEND.name.toLowerCase(), "get-records",
            replyAddress, JsonObject(), true, JsonObject(), IntegrationTest.tcpSocket
        )
    }

    override fun getRecord(uuid: String?, resultHandler: Handler<AsyncResult<Record>>?) {
        TODO("Not yet implemented")
    }

    override fun name() = "test-service-discovery"
}
