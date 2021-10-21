package spp.protocol.platform.client

import java.io.Serializable

data class ActiveMarker(
    val markerId: String,
    val connectedAt: Long,
    val developerId: String,
    val meta: Map<String, Any> = emptyMap()
) : Serializable
