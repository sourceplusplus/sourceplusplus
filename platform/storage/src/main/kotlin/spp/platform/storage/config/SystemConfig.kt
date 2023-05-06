package spp.platform.storage.config

import io.vertx.core.impl.ConcurrentHashSet
import java.util.stream.Stream

object SystemConfig {

    private val registeredConfigurations = ConcurrentHashSet<SystemConfiguration<*>>()

    fun <T> register(config: SystemConfiguration<T>): SystemConfiguration<T> {
        if (registeredConfigurations.any { it.name == config.name }) {
            throw IllegalArgumentException("System config ${config.name} already registered")
        }

        registeredConfigurations.add(config)
        return config
    }

    fun isValidConfig(config: String): Boolean {
        return registeredConfigurations.any { it.name == config }
    }

    fun values(): Stream<SystemConfiguration<*>> {
        return registeredConfigurations.stream()
    }

    fun get(config: String): SystemConfiguration<*> {
        return registeredConfigurations.firstOrNull { it.name == config }
            ?: throw IllegalArgumentException("System config $config not found")
    }
}
