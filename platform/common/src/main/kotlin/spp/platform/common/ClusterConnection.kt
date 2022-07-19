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
package spp.platform.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.spi.cluster.redis.RedisClusterManager
import io.vertx.spi.cluster.redis.config.RedisConfig
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.lookup.StringLookupFactory
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import java.io.File
import java.util.*

object ClusterConnection {
    private val BUILD = ResourceBundle.getBundle("build")
    private val log = KotlinLogging.logger {}
    private lateinit var vertx: Vertx
    private val lock = Any()
    private fun isVertxInitialized() = ::vertx.isInitialized
    lateinit var config: JsonObject

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
                log.trace { "Build id: " + BUILD.getString("build_id") }
                log.trace { "Build date: " + BUILD.getString("build_date") }
                log.trace { "Using configuration: " + config.encode() }

                if (config.getJsonObject("storage").getString("selector") == "memory") {
                    log.info("Using standalone mode")
                    vertx = Vertx.vertx()
                } else {
                    log.info("Using clustered mode")
                    val storageSelector = config.getJsonObject("storage").getString("selector")
                    val storageConfig = config.getJsonObject("storage").getJsonObject(storageSelector)
                    val host = storageConfig.getString("host")
                    val port = storageConfig.getString("port").toInt()
                    val clusterStorageAddress = "redis://$host:$port"
                    log.debug("Cluster storage address: $clusterStorageAddress")

                    val clusterManager = RedisClusterManager(
                        RedisConfig()
                            .setKeyNamespace("cluster")
                            .addEndpoint(clusterStorageAddress)
                    )
                    val options = VertxOptions().setClusterManager(clusterManager)
                    runBlocking {
                        vertx = Vertx.clusteredVertx(options).await()
                    }
                }
            }
        }
        return vertx
    }
}
