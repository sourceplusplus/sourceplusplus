package spp.protocol.processor.status

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.beans.ConstructorProperties
import java.io.Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProcessorConnection @ConstructorProperties("processorId", "connectionTime") constructor(
    var processorId: String,
    var connectionTime: Long
) : Serializable
