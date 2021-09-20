package spp.protocol.probe.error;

public class LiveInstrumentException extends RuntimeException {

    private final ErrorType errorType;

    public LiveInstrumentException(ErrorType errorType, String message) {
        super(message, null, true, false);
        this.errorType = errorType;
    }

    public LiveInstrumentException toEventBusException() {
        return new LiveInstrumentException(errorType, "EventBusException:LiveInstrumentException[" + errorType + "]: " + getMessage());
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public String toString() {
        return getLocalizedMessage();
    }

    public enum ErrorType {
        CLASS_NOT_FOUND,
        CONDITIONAL_FAILED
    }
}
