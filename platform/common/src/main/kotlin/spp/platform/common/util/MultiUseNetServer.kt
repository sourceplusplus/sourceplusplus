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
package spp.platform.common.util

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.*
import io.vertx.core.net.impl.TCPServerBase
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge
import io.vertx.kotlin.coroutines.await
import mu.KotlinLogging
import org.joor.Reflect
import java.util.*

/**
 * Allows for HTTP server(s) and TCP server(s) to reside on the same port simultaneously.
 */
class MultiUseNetServer(private val vertx: Vertx) {

    private val log = KotlinLogging.logger {}
    private val useMap = IdentityHashMap<UseDecider, UsableNetClient>()

    fun addUse(server: TCPServerBase, decider: UseDecider? = null): MultiUseNetServer {
        val serverOptions = Reflect.on(server).get<TCPSSLOptions>("options")
        val options = NetClientOptions()
            .setSsl(serverOptions.isSsl)
            .setKeyCertOptions(serverOptions.keyCertOptions)
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
            error("No use deciders added")
        }

        val serverOptions = NetServerOptions()
            .setSsl(options?.isSsl ?: false)
            .setKeyCertOptions(options?.keyCertOptions)
        val server = vertx.createNetServer(serverOptions)
        server.connectHandler { orig ->
            log.trace { "Received connection from ${orig.remoteAddress()}" }
            orig.handler { origBuffer ->
                orig.pause()
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
                    log.trace { "Sending connection to ${usableNetClient.host}:${usableNetClient.port}" }

                    usableNetClient.netClient.connect(usableNetClient.port, usableNetClient.host).onSuccess { sock ->
                        orig.pipeTo(sock)
                        sock.pipeTo(orig)
                        sock.write(origBuffer).onSuccess {
                            orig.resume()
                        }.onFailure {
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
