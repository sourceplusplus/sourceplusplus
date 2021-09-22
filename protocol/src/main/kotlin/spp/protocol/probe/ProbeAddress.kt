package spp.protocol.probe

enum class ProbeAddress(val address: String) {
    REMOTE_REGISTERED("spp.probe.status.remote-registered"),
    LIVE_BREAKPOINT_REMOTE("spp.probe.command.live-breakpoint-remote"),
    LIVE_LOG_REMOTE("spp.probe.command.live-log-remote");
}
