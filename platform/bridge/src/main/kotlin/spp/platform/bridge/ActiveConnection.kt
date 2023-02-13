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
package spp.platform.bridge

import io.vertx.core.Future
import io.vertx.core.net.NetSocket
import io.vertx.ext.bridge.BaseBridgeEvent
import io.vertx.ext.web.handler.sockjs.SockJSSocket

class ActiveConnection(private val netSocket: NetSocket? = null, private val sockJSSocket: SockJSSocket? = null) {
    lateinit var id: String
    var lastPing = System.currentTimeMillis()

    fun close(): Future<Void> {
        return if (netSocket != null) {
            netSocket.close()
        } else if (sockJSSocket != null) {
            sockJSSocket.end()
        } else {
            Future.failedFuture("No socket to close")
        }
    }

    companion object {
        fun from(event: BaseBridgeEvent): ActiveConnection {
            return when (event) {
                is io.vertx.ext.eventbus.bridge.tcp.BridgeEvent -> ActiveConnection(netSocket = event.socket())
                is io.vertx.ext.web.handler.sockjs.BridgeEvent -> ActiveConnection(sockJSSocket = event.socket())
                else -> throw IllegalArgumentException("Unknown bridge event type")
            }
        }
    }
}
