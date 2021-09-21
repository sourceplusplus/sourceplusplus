package spp.protocol.platform

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil

enum class PlatformAddress(  //todo: spp.probe makes more sense?
    val address: String
) {
    PROCESSOR_CONNECTED("spp.platform.status.processor-connected"), PROCESSOR_DISCONNECTED("spp.platform.status.processor-disconnected"), PROBE_CONNECTED(
        "spp.platform.status.probe-connected"
    ),
    PROBE_DISCONNECTED("spp.platform.status.probe-disconnected"), LIVE_BREAKPOINT_REMOVED("spp.platform.status.live-breakpoint-removed"),  //todo: spp.probe makes more sense?
    LIVE_BREAKPOINT_APPLIED("spp.platform.status.live-breakpoint-applied"),  //todo: spp.probe makes more sense?
    LIVE_BREAKPOINTS("spp.platform.status.live-breakpoints"),  //todo: spp.probe makes more sense?

    //MARKER_CONNECTED("sm.status.marker-connected"),
    MARKER_DISCONNECTED("spp.platform.status.marker-disconnected"), GENERATE_PROBE("spp.platform.generate-probe"), LIVE_LOG_REMOVED(
        "spp.platform.status.live-log-removed"
    ),  //todo: spp.probe makes more sense?
    LIVE_LOG_APPLIED("spp.platform.status.live-log-applied"),  //todo: spp.probe makes more sense?
    LIVE_LOGS("spp.platform.status.live-logs");
}