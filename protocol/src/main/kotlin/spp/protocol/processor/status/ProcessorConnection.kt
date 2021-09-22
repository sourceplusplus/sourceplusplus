package spp.protocol.processor.status

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProcessorConnection(
    var processorId: String,
    var connectionTime: Long
)
