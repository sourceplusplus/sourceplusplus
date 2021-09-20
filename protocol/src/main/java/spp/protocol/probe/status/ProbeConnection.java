package spp.protocol.probe.status;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProbeConnection implements Serializable {

    private String probeId;
    private long connectionTime;

    @SuppressWarnings("unused")
    public ProbeConnection() {
    }

    public ProbeConnection(String probeId, long connectionTime) {
        this.probeId = probeId;
        this.connectionTime = connectionTime;
    }

    public String getProbeId() {
        return probeId;
    }

    @SuppressWarnings("unused")
    public void setProbeId(String probeId) {
        this.probeId = probeId;
    }

    public long getConnectionTime() {
        return connectionTime;
    }

    @SuppressWarnings("unused")
    public void setConnectionTime(long connectionTime) {
        this.connectionTime = connectionTime;
    }
}
