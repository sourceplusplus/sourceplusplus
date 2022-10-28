/*
 * Source++, the continuous feedback platform for developers.
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
package spp.platform.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.common.base.CaseFormat
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.spi.cluster.redis.RedisClusterManager
import io.vertx.spi.cluster.redis.config.RedisConfig
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.lookup.StringLookupFactory
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import spp.platform.common.util.MultiUseNetServer
import spp.platform.common.util.args
import java.io.File
import java.util.*

object ClusterConnection {

    private val BUILD = ResourceBundle.getBundle("build")
    private val log = KotlinLogging.logger {}
    private lateinit var vertx: Vertx
    private val lock = Any()
    private fun isVertxInitialized() = ::vertx.isInitialized
    lateinit var discovery: ServiceDiscovery
    lateinit var config: JsonObject
    lateinit var router: Router
    lateinit var multiUseNetServer: MultiUseNetServer

    fun getVertx(): Vertx {
        if (!isVertxInitialized()) {
            synchronized(lock) {
                config = JsonObject(
                    StringSubstitutor(StringLookupFactory.INSTANCE.environmentVariableStringLookup()).replace(
                        ObjectMapper().writeValueAsString(
                            YAMLMapper().readValue(File("config/spp-platform.yml"), Object::class.java)
                        )
                    )
                )

                Configurator.setLevel(
                    "spp",
                    Level.getLevel(
                        config.getJsonObject("spp-platform")
                            .getJsonObject("logging")
                            .getString("level").uppercase()
                    )
                )
                log.info("Booting Source++ Platform [v${BUILD.getString("build_version")}]")
                log.debug { "Build id: " + BUILD.getString("build_id") }
                log.debug { "Build date: " + BUILD.getString("build_date") }
                log.trace { "Using configuration: " + config.encode() }

                if (config.getJsonObject("storage").getString("selector") == "memory") {
                    log.info("Using in-memory storage")
                    val vertx = Vertx.vertx()
                    discovery = ServiceDiscovery.create(vertx)
                    router = Router.router(vertx)
                    multiUseNetServer = MultiUseNetServer(vertx)
                    ClusterConnection.vertx = vertx
                } else {
                    val storageSelector = config.getJsonObject("storage").getString("selector")
                    val storageName = CaseFormat.LOWER_CAMEL.to(
                        CaseFormat.LOWER_HYPHEN,
                        storageSelector.substringAfterLast(".").removeSuffix("Storage")
                    )
                    val storageConfig = config.getJsonObject("storage").getJsonObject(storageName)
                    val clusterMode = storageConfig.getJsonObject("cluster")
                        ?.getString("enabled")?.toBooleanStrict() ?: false
                    log.info("Using $storageSelector storage (cluster mode: $clusterMode)")

                    val host = storageConfig.getString("host")
                    val port = storageConfig.getString("port").toInt()
                    val storageAddress = "redis://$host:$port"
                    log.debug { "Storage address: {}".args(storageAddress) }

                    val options = VertxOptions().apply {
                        if (clusterMode) {
                            clusterManager = RedisClusterManager(
                                RedisConfig()
                                    .setKeyNamespace("cluster")
                                    .addEndpoint(storageAddress)
                            )
                        }
                    }
                    runBlocking {
                        val vertx = if (clusterMode) {
                            Vertx.clusteredVertx(options).await()
                        } else {
                            Vertx.vertx(options)
                        }

                        discovery = ServiceDiscovery.create(vertx)
                        router = Router.router(vertx)
                        multiUseNetServer = MultiUseNetServer(vertx)
                        ClusterConnection.vertx = vertx
                    }
                }
            }
        }
        return vertx
    }
}
