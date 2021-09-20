package integration

import com.sourceplusplus.protocol.instrument.LiveInstrument
import com.sourceplusplus.protocol.instrument.breakpoint.LiveBreakpoint
import com.sourceplusplus.protocol.instrument.log.LiveLog
import io.vertx.core.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.cli.Main

class BatchLiveInstrumentCLI : CLIIntegrationTest() {

    @Test
    fun create100LiveBreakpoints() {
        val origOut = System.out
        val interceptor = Interceptor(origOut)
        System.setOut(interceptor)

        //100 live bps
        val addedLiveBps = mutableListOf<LiveInstrument>()
        for (i in 0..99) {
            Main.main(
                arrayOf(
                    "-v",
                    "-c", "../../platform/config/spp-platform.crt",
                    "-k", "../../platform/config/spp-platform.key",
                    "developer",
                    "add-live-breakpoint",
                    "integration.BatchLiveInstrumentCLI", i.toString(),
                )
            )
            val addedLiveBp = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
            addedLiveBps.add(addedLiveBp)
            assertNotNull(addedLiveBp.id)
            interceptor.clear()
        }

        //get live instruments
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "get-live-instruments"
            )
        )

        val liveInstruments = toList(interceptor.output.toString(), LiveInstrument::class)
        assertEquals(100, liveInstruments.size)
        interceptor.clear()

        //todo: need clear-live-instruments method
        addedLiveBps.forEach {
            //remove live instrument
            Main.main(
                arrayOf(
                    "-v",
                    "-c", "../../platform/config/spp-platform.crt",
                    "-k", "../../platform/config/spp-platform.key",
                    "developer",
                    "remove-live-instrument",
                    it.id!!
                )
            )
            val removedLiveBp = Json.decodeValue(interceptor.output.toString(), LiveBreakpoint::class.java)
            assertEquals(it.id, removedLiveBp.id)
            interceptor.clear()
        }
    }
}
