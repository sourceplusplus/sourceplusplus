package spp.protocol.probe.command

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveInstrumentCommand(
    var commandType: CommandType,
    var context: LiveInstrumentContext
) {

    data class Response(
        var isSuccess: Boolean,
        var fault: String? = null,
        var timestamp: Long,
        var context: LiveInstrumentContext
    )

    enum class CommandType {
        GET_LIVE_INSTRUMENTS,
        ADD_LIVE_INSTRUMENT,
        REMOVE_LIVE_INSTRUMENT
    }
}
