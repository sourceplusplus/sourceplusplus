package spp.protocol.processor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil

enum class ProcessorAddress(val address: String) {
    LOGGING_PROCESSOR("sw.processor.logging"), LIVE_VIEW_PROCESSOR("sw.processor.live-view"), LIVE_INSTRUMENT_PROCESSOR(
        "sw.processor.live-instrument"
    ),
    BREAKPOINT_HIT("spp.provider.status.breakpoint-hit"), LOG_HIT("spp.provider.status.log-hit"), VIEW_SUBSCRIPTION_EVENT(
        "spp.provider.status.view-subscription-event"
    ),
    SET_LOG_PUBLISH_RATE_LIMIT("spp.provider.setting.log-publish-rate-limit");
}