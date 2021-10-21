package spp.protocol.platform.client

import java.io.Serializable

data class ActiveProcessor(
    val processorId: String,
    val connectedAt: Long,
    val meta: MutableMap<String, Any> = mutableMapOf()
) : Serializable
