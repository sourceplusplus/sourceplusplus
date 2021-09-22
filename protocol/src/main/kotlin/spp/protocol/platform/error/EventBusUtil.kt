package spp.protocol.platform.error

import spp.protocol.probe.error.LiveInstrumentException

object EventBusUtil {

    fun fromEventBusException(exception: String): Exception {
        return if (exception.startsWith("EventBusException")) {
            var exceptionType = exception.substringAfter("EventBusException:")
            exceptionType = exceptionType.substringBefore("[")
            var exceptionParams = exception.substringAfter("[")
            exceptionParams = exceptionParams.substringBefore("]")
            val exceptionMessage = exception.substringAfter("]: ").trim { it <= ' ' }
            if (LiveInstrumentException::class.java.simpleName == exceptionType) {
                LiveInstrumentException(
                    LiveInstrumentException.ErrorType.valueOf(exceptionParams),
                    exceptionMessage
                ).toEventBusException()
            } else {
                throw UnsupportedOperationException(exceptionType)
            }
        } else {
            throw IllegalArgumentException(exception)
        }
    }
}
