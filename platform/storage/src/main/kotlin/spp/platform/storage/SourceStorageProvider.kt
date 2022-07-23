package spp.platform.storage

import com.google.common.base.CaseFormat
import io.vertx.ext.web.sstore.LocalSessionStore
import io.vertx.ext.web.sstore.redis.RedisSessionStore
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.apache.skywalking.oap.server.library.module.ModuleConfig
import org.apache.skywalking.oap.server.library.module.ModuleDefine
import org.apache.skywalking.oap.server.library.module.ModuleProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.platform.common.ClusterConnection.config
import kotlin.system.exitProcess

class SourceStorageModule : ModuleDefine("spp-platform-storage") {
    override fun services(): Array<Class<*>> = emptyArray()
}

class SourceStorageProvider : ModuleProvider() {
    private val log: Logger = LoggerFactory.getLogger(SourceStorageProvider::class.java)

    override fun name(): String = "default"
    override fun module(): Class<out ModuleDefine> = SourceStorageModule::class.java
    override fun createConfigBeanIfAbsent(): ModuleConfig? = null
    override fun prepare() = Unit

    override fun start() {
        try {
            log.info("Starting spp-storage")
            val vertx = ClusterConnection.getVertx()
            runBlocking(vertx.dispatcher()) {
                when (val storageSelector = config.getJsonObject("storage").getString("selector")) {
                    "memory" -> {
                        log.info("Using in-memory storage")
                        SourceStorage.setup(MemoryStorage(vertx), config)
                        SourceStorage.initSessionStore(LocalSessionStore.create(vertx))
                    }
                    "redis" -> {
                        log.info("Using Redis storage")
                        val redisStorage = RedisStorage()
                        redisStorage.init(vertx, config.getJsonObject("storage").getJsonObject("redis"))

                        val installDefaults = config.getJsonObject("storage").getJsonObject("redis")
                            .getString("install_defaults")?.toBooleanStrictOrNull() != false
                        SourceStorage.setup(redisStorage, config, installDefaults)
                        SourceStorage.initSessionStore(RedisSessionStore.create(vertx, redisStorage.redisClient))
                    }
                    else -> {
                        Class.forName(storageSelector, false, SourceStorage::class.java.classLoader)
                        log.info("Using custom storage: $storageSelector")
                        val storageClass = Class.forName(storageSelector)
                        val customStorage = storageClass.getDeclaredConstructor().newInstance() as CoreStorage
                        val storageName = CaseFormat.LOWER_CAMEL.to(
                            CaseFormat.LOWER_HYPHEN,
                            storageClass.simpleName.removeSuffix("Storage")
                        )
                        customStorage.init(vertx, config.getJsonObject("storage").getJsonObject(storageName))

                        val installDefaults = config.getJsonObject("storage").getJsonObject(storageName)
                            .getString("install_defaults")?.toBooleanStrictOrNull() != false
                        SourceStorage.setup(customStorage, config, installDefaults)
                        SourceStorage.initSessionStore(LocalSessionStore.create(vertx)) //todo: dynamic sessionStore
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to start spp-storage.", e)
            exitProcess(-1)
        }
    }

    override fun notifyAfterCompleted() = Unit
    override fun requiredModules(): Array<String> = emptyArray()
}
