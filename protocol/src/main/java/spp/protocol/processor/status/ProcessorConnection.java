package spp.protocol.processor.status;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessorConnection implements Serializable {

    private String processorId;
    private long connectionTime;

    public ProcessorConnection() {
    }

    public ProcessorConnection(String processorId, long connectionTime) {
        this.processorId = processorId;
        this.connectionTime = connectionTime;
    }

    public String getProcessorId() {
        return processorId;
    }

    @SuppressWarnings("unused")
    public void setProcessorId(String processorId) {
        this.processorId = processorId;
    }

    public long getConnectionTime() {
        return connectionTime;
    }

    @SuppressWarnings("unused")
    public void setConnectionTime(long connectionTime) {
        this.connectionTime = connectionTime;
    }
}
