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
package spp.platform.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.common.base.CaseFormat
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.dropwizard.DropwizardMetricsOptions
import io.vertx.ext.dropwizard.impl.VertxMetricsFactoryImpl
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.spi.cluster.redis.RedisClusterManager
import io.vertx.spi.cluster.redis.config.LockConfig
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
import java.util.regex.Pattern

/**
 * Shared connections and configuration for all cluster nodes.
 */
object ClusterConnection {

    val BUILD: ResourceBundle = ResourceBundle.getBundle("build")
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

                val options = VertxOptions().setMetricsOptions(
                    DropwizardMetricsOptions()
                        .setFactory(VertxMetricsFactoryImpl())
                        .setEnabled(true)
                )

                var clusterMode = false
                if (config.getJsonObject("storage").getString("selector") != "memory") {
                    val storageSelector = config.getJsonObject("storage").getString("selector")
                    val storageName = CaseFormat.LOWER_CAMEL.to(
                        CaseFormat.LOWER_HYPHEN,
                        storageSelector.substringAfterLast(".").removeSuffix("Storage")
                    )
                    val storageConfig = config.getJsonObject("storage").getJsonObject(storageName)
                    clusterMode = storageConfig.getJsonObject("cluster")
                        ?.getString("enabled")?.toBooleanStrict() ?: false

                    val host = storageConfig.getString("host")
                    val port = storageConfig.getString("port").toInt()
                    val storageAddress = "redis://$host:$port"
                    log.debug { "Storage address: {} (cluster mode: {})".args(storageAddress, clusterMode) }

                    if (clusterMode) {
                        options.clusterManager = RedisClusterManager(
                            RedisConfig()
                                .setKeyNamespace("cluster")
                                .addEndpoint(storageAddress)
                                .addLock(LockConfig(Pattern.compile("expiring_shared_data:.*")).setLeaseTime(5000))
                        )
                    }
                }

                val vertx = if (clusterMode) {
                    runBlocking { Vertx.clusteredVertx(options).await() }
                } else {
                    Vertx.vertx(options)
                }
                discovery = ServiceDiscovery.create(vertx)
                router = Router.router(vertx)
                multiUseNetServer = MultiUseNetServer(vertx)
                ClusterConnection.vertx = vertx

                vertx.eventBus().addInboundInterceptor<Any> {
                    val headers = it.message().headers()
                    if (headers != null) {
                        Vertx.currentContext().removeLocal("client_id")
                        headers.get("client_id")?.let { clientId ->
                            Vertx.currentContext().putLocal("client_id", clientId)
                        }
                        Vertx.currentContext().removeLocal("client_secret")
                        headers.get("client_secret")?.let { clientSecret ->
                            Vertx.currentContext().putLocal("client_secret", clientSecret)
                        }
                        Vertx.currentContext().removeLocal("tenant_id")
                        headers.get("tenant_id")?.let { tenantId ->
                            Vertx.currentContext().putLocal("tenant_id", tenantId)
                        }
                    }
                    it.next()
                }
            }
        }
        return vertx
    }

    fun getConfig(id: String): String? {
        val namespace = id.split(".")
        var config: JsonObject? = ClusterConnection.config
        for (i in namespace.indices) {
            if (config == null) {
                return null
            }
            if (i == namespace.size - 1) {
                return config.getString(namespace[i])
            }
            config = config.getJsonObject(namespace[i])
        }
        return null
    }
}
