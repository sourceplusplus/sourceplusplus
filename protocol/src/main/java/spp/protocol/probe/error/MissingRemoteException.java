package spp.protocol.probe.error;

public class MissingRemoteException extends RuntimeException {

    public MissingRemoteException(String remote) {
        super(remote, null, true, false);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public String toString() {
        return getLocalizedMessage();
    }
}
