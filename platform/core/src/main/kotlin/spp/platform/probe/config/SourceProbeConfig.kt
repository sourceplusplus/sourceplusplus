package spp.platform.probe.config

import java.util.*

private val BUILD: ResourceBundle = ResourceBundle.getBundle("build")

data class SourceProbeConfig(
    val platformHost: String,
    val skywalkingServiceName: String,
    val skywalkingBackendService: String = "$platformHost:11800",
    val platformPort: Int = 5450,
    val probeLocation: String = "probe",
    val probeVersion: String = BUILD.getString("build_version")
)
