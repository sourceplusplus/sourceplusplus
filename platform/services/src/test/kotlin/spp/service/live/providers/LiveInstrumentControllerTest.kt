package spp.service.live.providers

import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.probe.ProbeAddress

@ExtendWith(VertxExtension::class)
class LiveInstrumentControllerTest {

    @Test
    fun expiredPendingInstrument(vertx: Vertx, testContext: VertxTestContext) {
        vertx.eventBus().consumer<Any>(ProbeAddress.LIVE_BREAKPOINT_REMOTE.address + ":" + "probeId") {
            //ignore
        }

        val instrumentController = LiveInstrumentController(vertx)
        instrumentController.addBreakpoint(
            "system",
            LiveBreakpoint(
                LiveSourceLocation("test.LiveInstrumentControllerTest", 1),
                expiresAt = System.currentTimeMillis(),
                pending = true
            )
        )

        GlobalScope.launch {
            delay(2000) //wait for bp to be expired

            testContext.verify {
                assertEquals(0, instrumentController.getLiveInstruments().size)
                testContext.completeNow()
            }
        }
    }
}
