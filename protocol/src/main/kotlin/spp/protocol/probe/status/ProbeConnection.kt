package spp.protocol.probe.status

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProbeConnection(
    var probeId: String,
    var connectionTime: Long
)
