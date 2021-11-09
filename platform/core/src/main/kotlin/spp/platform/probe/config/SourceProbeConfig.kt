package spp.platform.probe.config

data class SourceProbeConfig(
    val platformHost: String,
    val skywalkingServiceName: String,
    val skywalkingBackendService: String = "$platformHost:11800",
    val platformPort: Int = 5450,
    val probeVersion: String
)
