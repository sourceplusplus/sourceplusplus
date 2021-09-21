package spp.protocol.probe

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil

enum class ProbeAddress(val address: String) {
    REMOTE_REGISTERED("spp.probe.status.remote-registered"), LIVE_BREAKPOINT_REMOTE("spp.probe.command.live-breakpoint-remote"), LIVE_LOG_REMOTE(
        "spp.probe.command.live-log-remote"
    );
}