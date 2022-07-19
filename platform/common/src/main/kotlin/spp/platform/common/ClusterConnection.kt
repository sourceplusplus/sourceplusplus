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

                log.info("Booting Source++ Platform [v${BUILD.getString("build_version")}]")
                log.trace { "Build id: " + BUILD.getString("build_id") }
                log.trace { "Build date: " + BUILD.getString("build_date") }
                log.trace { "Using configuration: " + config.encode() }

                if (config.getJsonObject("storage").getString("selector") == "memory") {
                    log.info("Using standalone mode")
                    vertx = Vertx.vertx()
                } else {
                    log.info("Using clustered mode")
                    val scheme = "redis"
                    val host = "redis"// "127.0.0.1"
                    val port = "6379"
                    val defaultAddress = "$scheme://$host:$port"
                    val clusterManager = RedisClusterManager(
                        RedisConfig()
                            .setKeyNamespace("cluster")
                            .addEndpoint(defaultAddress)
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
