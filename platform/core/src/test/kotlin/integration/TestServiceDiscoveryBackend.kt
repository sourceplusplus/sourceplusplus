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
        val consumer = vertx.eventBus().localConsumer<JsonArray>(replyAddress)
        consumer.handler {
            resultHandler.handle(Future.succeededFuture(it.body().map { Record(it as JsonObject) }.toMutableList()))
            consumer.unregister()
        }
        FrameHelper.sendFrame(
            BridgeEventType.SEND.name.lowercase(), "get-records",
            replyAddress, JsonObject(), true, JsonObject(), PlatformIntegrationTest.tcpSocket
        )
    }

    override fun getRecord(uuid: String?, resultHandler: Handler<AsyncResult<Record>>?) {
        TODO("Not yet implemented")
    }

    override fun name() = "test-service-discovery"
}
