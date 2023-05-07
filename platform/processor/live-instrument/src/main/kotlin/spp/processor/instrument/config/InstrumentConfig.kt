package spp.processor.instrument.config

import spp.platform.storage.config.SystemConfiguration

object InstrumentConfig {

    suspend fun install() {
        LogPublishRateLimit.install()
        LogPublishCacheTTL.install()
    }

    val LogPublishRateLimit = SystemConfiguration(
        "spp-platform.instrument.log_publish_rate_limit",
        defaultValue = 1000L,
        validator = { it.toLong() },
        mapper = { it.toLong() }
    )
    val LogPublishCacheTTL = SystemConfiguration(
        "spp-platform.instrument.log_publish_cache_ttl",
        defaultValue = 60_000L,
        validator = { it.toLong() },
        mapper = { it.toLong() }
    )
}
