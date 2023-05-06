package spp.platform.storage.config

import io.vertx.core.impl.ConcurrentHashSet
import java.util.stream.Stream

object PlatformConfig {

    private val validConfig = ConcurrentHashSet<DynamicConfiguration<*>>()

    fun <T> register(config: DynamicConfiguration<T>): DynamicConfiguration<T> {
        if (validConfig.any { it.name == config.name }) {
            throw IllegalArgumentException("Config ${config.name} already registered")
        }

        validConfig.add(config)
        return config
    }

    fun isValidConfig(config: String): Boolean {
        return validConfig.any { it.name == config }
    }

    fun values(): Stream<DynamicConfiguration<*>> {
        return validConfig.stream()
    }

    fun get(config: String): DynamicConfiguration<*> {
        return validConfig.firstOrNull { it.name == config }
            ?: throw IllegalArgumentException("Config $config not found")
    }
}
