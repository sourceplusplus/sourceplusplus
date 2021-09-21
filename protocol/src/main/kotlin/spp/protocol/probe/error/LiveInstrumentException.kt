package spp.protocol.probe.error

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil
import java.lang.RuntimeException

class LiveInstrumentException(val errorType: ErrorType, message: String?) :
    RuntimeException(message, null, true, false) {
    fun toEventBusException(): LiveInstrumentException {
        return LiveInstrumentException(errorType, "EventBusException:LiveInstrumentException[$errorType]: $message")
    }

    @Synchronized
    override fun fillInStackTrace(): Throwable {
        return this
    }

    override fun toString(): String {
        return localizedMessage
    }

    enum class ErrorType {
        CLASS_NOT_FOUND, CONDITIONAL_FAILED
    }
}