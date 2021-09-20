package spp.protocol.probe;

public enum ProbeAddress {

    REMOTE_REGISTERED("spp.probe.status.remote-registered"),
    LIVE_BREAKPOINT_REMOTE("spp.probe.command.live-breakpoint-remote"),
    LIVE_LOG_REMOTE("spp.probe.command.live-log-remote");

    public final String address;

    ProbeAddress(String address) {
        this.address = address;
    }
}
