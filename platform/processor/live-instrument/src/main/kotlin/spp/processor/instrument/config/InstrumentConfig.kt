package spp.processor.instrument.config

import spp.platform.common.ClusterConnection
import spp.platform.storage.config.DynamicConfiguration

object InstrumentConfig {

    private val config = ClusterConnection.config.getJsonObject("spp-platform")?.getJsonObject("instrument")

    suspend fun install() {
        LogPublishRateLimit.install()
        LogPublishCacheTTL.install()
    }

    val LogPublishRateLimit = DynamicConfiguration(
        "spp-platform.instrument.log-publish-rate-limit",
        config?.getString("log-publish-rate-limit")?.toLongOrNull() ?: 1000L,
        validator = { it.toLong() },
        mapper = { it.toLong() }
    )
    val LogPublishCacheTTL = DynamicConfiguration(
        "spp-platform.instrument.log-publish-cache-ttl",
        defaultValue = config?.getString("log-publish-cache-ttl")?.toLongOrNull() ?: 60_000L,
        validator = { it.toLong() },
        mapper = { it.toLong() }
    )
}
