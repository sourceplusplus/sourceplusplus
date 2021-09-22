package spp.protocol.probe.command

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.vertx.core.json.Json
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil
import java.io.Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
class LiveInstrumentCommand : Serializable {
    var commandType: CommandType? = null
    lateinit var context: LiveInstrumentContext

    class Response : Serializable {
        var isSuccess = false
        var fault: String? = null
        var timestamp: Long = 0
        lateinit var context: LiveInstrumentContext
    }

    enum class CommandType {
        GET_LIVE_INSTRUMENTS, ADD_LIVE_INSTRUMENT, REMOVE_LIVE_INSTRUMENT
    }
}