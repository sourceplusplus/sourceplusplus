package spp.protocol.platform.client

import java.io.Serializable

data class ActiveProbe(
    val probeId: String,
    val connectedAt: Long,
    val remotes: MutableList<String> = mutableListOf(),
    val meta: MutableMap<String, Any> = mutableMapOf()
) : Serializable
