package spp.protocol.probe.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.json.Json;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LiveInstrumentCommand implements Serializable {

    private CommandType commandType;
    private LiveInstrumentContext context;

    public LiveInstrumentCommand() {
    }

    public CommandType getCommandType() {
        return commandType;
    }

    public LiveInstrumentContext getContext() {
        return context;
    }

    public void setContext(LiveInstrumentContext context) {
        this.context = context;
    }

    public void setCommandType(CommandType commandType) {
        this.commandType = commandType;
    }

    public static LiveInstrumentCommand fromJson(String json) {
        return Json.decodeValue(json, LiveInstrumentCommand.class);
    }

    public static class Response implements Serializable {

        private boolean success;
        private String fault;
        private long timestamp;
        private LiveInstrumentContext context;

        public Response() {
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getFault() {
            return fault;
        }

        @SuppressWarnings("unused")
        public void setFault(String fault) {
            this.fault = fault;
        }

        @SuppressWarnings("unused")
        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public LiveInstrumentContext getContext() {
            return context;
        }

        public void setContext(LiveInstrumentContext context) {
            this.context = context;
        }
    }

    public enum CommandType {
        GET_LIVE_INSTRUMENTS,
        ADD_LIVE_INSTRUMENT,
        REMOVE_LIVE_INSTRUMENT
    }
}
