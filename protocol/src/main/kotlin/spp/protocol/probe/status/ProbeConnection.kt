package spp.protocol.probe.status

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil
import java.io.Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
class ProbeConnection : Serializable {
    lateinit var probeId: String
    var connectionTime: Long = 0

    constructor() {}
    constructor(probeId: String, connectionTime: Long) {
        this.probeId = probeId
        this.connectionTime = connectionTime
    }
}