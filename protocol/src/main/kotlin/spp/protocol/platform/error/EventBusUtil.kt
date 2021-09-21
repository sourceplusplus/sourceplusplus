package spp.protocol.platform.error

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil
import spp.protocol.probe.error.LiveInstrumentException
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.lang.UnsupportedOperationException

object EventBusUtil {
    fun fromEventBusException(exception: String): Exception? {
        return if (exception.startsWith("EventBusException")) {
            var exceptionType = substringAfter(exception, "EventBusException:")
            exceptionType = substringBefore(exceptionType, "[")
            var exceptionParams = substringAfter(exception, "[")
            exceptionParams = substringBefore(exceptionParams, "]")
            val exceptionMessage = substringAfter(exception, "]: ").trim { it <= ' ' }
            if (LiveInstrumentException::class.java.simpleName == exceptionType) {
                LiveInstrumentException(
                    LiveInstrumentException.ErrorType.valueOf(
                        exceptionParams
                    ), exceptionMessage
                )
                    .toEventBusException()
            } else {
                throw UnsupportedOperationException(exceptionType)
            }
        } else {
            throw IllegalArgumentException(exception)
        }
    }

    private fun substringAfter(value: String, delimiter: String): String {
        val index = value.indexOf(delimiter)
        return if (index == -1) value else value.substring(index + delimiter.length)
    }

    private fun substringBefore(value: String, delimiter: String): String {
        val index = value.indexOf(delimiter)
        return if (index == -1) value else value.substring(0, index)
    }
}