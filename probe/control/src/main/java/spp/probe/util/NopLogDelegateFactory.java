package spp.probe.util;

import io.vertx.core.spi.logging.LogDelegate;
import io.vertx.core.spi.logging.LogDelegateFactory;

public class NopLogDelegateFactory implements LogDelegateFactory {

    private final LogDelegate nop = new LogDelegate() {
        @Override
        public boolean isWarnEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public void fatal(Object message) {
        }

        @Override
        public void fatal(Object message, Throwable t) {
        }

        @Override
        public void error(Object message) {
        }

        @Override
        public void error(Object message, Object... params) {
        }

        @Override
        public void error(Object message, Throwable t) {
        }

        @Override
        public void error(Object message, Throwable t, Object... params) {
        }

        @Override
        public void warn(Object message) {
        }

        @Override
        public void warn(Object message, Object... params) {
        }

        @Override
        public void warn(Object message, Throwable t) {
        }

        @Override
        public void warn(Object message, Throwable t, Object... params) {
        }

        @Override
        public void info(Object message) {
        }

        @Override
        public void info(Object message, Object... params) {
        }

        @Override
        public void info(Object message, Throwable t) {
        }

        @Override
        public void info(Object message, Throwable t, Object... params) {
        }

        @Override
        public void debug(Object message) {
        }

        @Override
        public void debug(Object message, Object... params) {
        }

        @Override
        public void debug(Object message, Throwable t) {
        }

        @Override
        public void debug(Object message, Throwable t, Object... params) {
        }

        @Override
        public void trace(Object message) {
        }

        @Override
        public void trace(Object message, Object... params) {
        }

        @Override
        public void trace(Object message, Throwable t) {
        }

        @Override
        public void trace(Object message, Throwable t, Object... params) {
        }
    };

    @Override
    public boolean isAvailable() {
        return true; //needs to be true or gets the "Using ..." output line
    }

    @Override
    public LogDelegate createDelegate(String name) {
        return nop;
    }
}
