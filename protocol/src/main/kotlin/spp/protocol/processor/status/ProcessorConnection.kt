package spp.protocol.processor.status

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil
import java.io.Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
class ProcessorConnection : Serializable {
    lateinit var processorId: String
    var connectionTime: Long = 0

    constructor() {}
    constructor(processorId: String, connectionTime: Long) {
        this.processorId = processorId
        this.connectionTime = connectionTime
    }
}