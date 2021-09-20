package spp.protocol.platform.error;

import spp.protocol.probe.error.LiveInstrumentException;

import static spp.protocol.probe.error.LiveInstrumentException.ErrorType;

public class EventBusUtil {

    public static Exception fromEventBusException(String exception) {
        if (exception.startsWith("EventBusException")) {
            String exceptionType = substringAfter(exception, "EventBusException:");
            exceptionType = substringBefore(exceptionType, "[");
            String exceptionParams = substringAfter(exception, "[");
            exceptionParams = substringBefore(exceptionParams, "]");
            String exceptionMessage = substringAfter(exception, "]: ").trim();

            if (LiveInstrumentException.class.getSimpleName().equals(exceptionType)) {
                return new LiveInstrumentException(ErrorType.valueOf(exceptionParams), exceptionMessage)
                        .toEventBusException();
            } else {
                throw new UnsupportedOperationException(exceptionType);
            }
        } else {
            throw new IllegalArgumentException(exception);
        }
    }

    private static String substringAfter(String value, String delimiter) {
        int index = value.indexOf(delimiter);
        if (index == -1) return value;
        else return value.substring(index + delimiter.length());
    }

    private static String substringBefore(String value, String delimiter) {
        int index = value.indexOf(delimiter);
        if (index == -1) return value;
        else return value.substring(0, index);
    }
}
