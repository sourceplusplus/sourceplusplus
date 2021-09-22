package spp.protocol.probe.status

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.beans.ConstructorProperties
import java.io.Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProbeConnection @ConstructorProperties("probeId", "connectionTime") constructor(
    var probeId: String,
    var connectionTime: Long
) : Serializable
