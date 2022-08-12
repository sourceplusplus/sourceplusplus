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
package spp.platform.common.util

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetServer
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.impl.TCPServerBase
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.await
import mu.KotlinLogging
import org.joor.Reflect
import java.util.*

/**
 * Allows for an HTTP server and a TCP server to reside on the same port.
 */
class MultiUseNetServer(private val vertx: Vertx) {

    private val log = KotlinLogging.logger {}
    private val useMap = IdentityHashMap<UseDecider, UsableNetClient>()

    fun addUse(server: TCPServerBase, decider: UseDecider? = null): MultiUseNetServer {
        val options = NetClientOptions()
            .setSsl(server.sslHelper().isSSL)
            .setKeyCertOptions(Reflect.on(server.sslHelper()).get("keyCertOptions"))
            .setTrustAll(true)
        val netClient = vertx.createNetClient(options)
        useMap[decider ?: CatchAllUseDecider()] = UsableNetClient(netClient, "localhost", server.actualPort())
        return this
    }

    suspend fun <T : TCPServerBase> addUse(server: Future<in T>, decider: UseDecider? = null): MultiUseNetServer {
        val serverImpl = server.await()
        return if (serverImpl is TcpEventBusBridge) {
            addUse(Reflect.on(serverImpl).get("server"), decider)
        } else {
            addUse(serverImpl as TCPServerBase, decider)
        }
    }

    private data class UsableNetClient(
        val netClient: NetClient,
        val host: String,
        val port: Int
    )

    fun interface UseDecider {
        fun canUse(buffer: Buffer): Boolean
    }

    private class CatchAllUseDecider : UseDecider {
        override fun canUse(buffer: Buffer): Boolean = true
    }

    fun listen(options: HttpServerOptions? = null, port: Int): Future<NetServer> {
        if (useMap.isEmpty()) {
            throw IllegalStateException("No use deciders added")
        }

        val serverOptions = NetServerOptions()
            .setSsl(options?.isSsl ?: false)
            .setKeyCertOptions(options?.keyCertOptions)
        val server = vertx.createNetServer(serverOptions)
        server.connectHandler { orig ->
            orig.handler { origBuffer ->
                var usableServers = useMap.filter {
                    it.key !is CatchAllUseDecider && it.key.canUse(origBuffer)
                }
                if (usableServers.isEmpty()) {
                    usableServers = useMap.filter { it.key is CatchAllUseDecider }
                }
                if (usableServers.isEmpty()) {
                    orig.close()
                    log.error("No usable servers found")
                } else {
                    val usableNetClient = usableServers.values.first()
                    usableNetClient.netClient.connect(usableNetClient.port, usableNetClient.host).onSuccess { sock ->
                        orig.handler {
                            sock.write(it)
                        }
                        sock.handler {
                            orig.write(it)
                        }
                        sock.endHandler {
                            orig.end()
                        }
                        sock.write(origBuffer).onFailure {
                            log.error("Could not write to socket", it)
                        }
                    }.onFailure {
                        log.error("Could not connect to server", it)
                    }
                }
            }
        }
        return server.listen(port)
    }
}
