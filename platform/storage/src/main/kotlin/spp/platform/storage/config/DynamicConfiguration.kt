package spp.platform.storage.config

import spp.platform.storage.SourceStorage
import spp.platform.storage.config.DynamicConfiguration.ConfigChangeMapper
import spp.platform.storage.config.DynamicConfiguration.ConfigChangeValidator
import java.util.concurrent.atomic.AtomicReference

class DynamicConfiguration<T>(
    val name: String,
    val defaultValue: T,
    val validator: ConfigChangeValidator = ConfigChangeValidator { },
    val mapper: ConfigChangeMapper<T> = ConfigChangeMapper { it as T }
) {
    private val reference = AtomicReference(defaultValue)
    private val changeListeners = mutableListOf<(T) -> Unit>()

    fun get(): T {
        return reference.get()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun retrieve(): T {
        return SourceStorage.get("configuration:$name") ?: defaultValue
    }

    suspend fun set(value: T) {
        SourceStorage.put("configuration:$name", value as Any)
        reference.set(value)
        changeListeners.forEach { it(value) }
    }

    suspend fun set(value: String) {
        set(mapper.mapper(value))
    }

    suspend fun install() {
        val initial = retrieve()
        if (initial != null && initial != defaultValue) {
            set(initial)
        }

        PlatformConfig.register(this)
    }

    fun addChangeListener(listener: (T) -> Unit) {
        changeListeners.add(listener)
    }

    fun interface ConfigChangeValidator {
        fun validateChange(value: String)
    }

    fun interface ConfigChangeMapper<T> {
        fun mapper(value: String): T
    }

    override fun toString(): String = name
}
