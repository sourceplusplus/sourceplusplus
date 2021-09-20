package integration

import com.sourceplusplus.protocol.instrument.LiveInstrument
import com.sourceplusplus.protocol.instrument.breakpoint.LiveBreakpoint
import com.sourceplusplus.protocol.instrument.log.LiveLog
import io.vertx.core.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.cli.Main

class LiveInstrumentCLI : CLIIntegrationTest() {

    @Test
    fun addRemoveLiveLog() {
        val origOut = System.out
        val interceptor = Interceptor(origOut)
        System.setOut(interceptor)

        //add live log
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "add-live-log",
                "-h", "100",
                "integration.LiveInstrumentCLI",
                "1",
                "addRemoveLiveLog"
            )
        )
        val addedLiveLog = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertNotNull(addedLiveLog.id)
        assertEquals("addRemoveLiveLog", addedLiveLog.logFormat)
        assertEquals("integration.LiveInstrumentCLI", addedLiveLog.location.source)
        assertEquals(1, addedLiveLog.location.line)
        assertEquals(100, addedLiveLog.hitLimit)

        interceptor.clear()

        //remove live instrument
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "remove-live-instrument",
                addedLiveLog.id!!
            )
        )
        val removedLiveLog = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertEquals(addedLiveLog.id, removedLiveLog.id)
        assertEquals(addedLiveLog.logFormat, removedLiveLog.logFormat)
        assertEquals(addedLiveLog.location.source, removedLiveLog.location.source)
        assertEquals(addedLiveLog.location.line, removedLiveLog.location.line)
        assertEquals(addedLiveLog.hitLimit, removedLiveLog.hitLimit)
    }

    @Test
    fun addRemoveLiveBreakpoint() {
        val origOut = System.out
        val interceptor = Interceptor(origOut)
        System.setOut(interceptor)

        //add live breakpoint
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "add-live-breakpoint",
                "integration.LiveInstrumentCLI", "2",
            )
        )
        val removedLiveBp = Json.decodeValue(interceptor.output.toString(), LiveBreakpoint::class.java)
        assertNotNull(removedLiveBp.id)
        assertEquals("integration.LiveInstrumentCLI", removedLiveBp.location.source)
        assertEquals(2, removedLiveBp.location.line)
        assertEquals(1, removedLiveBp.hitLimit)

        interceptor.clear()

        //remove live instrument
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "remove-live-instrument",
                removedLiveBp.id!!
            )
        )
        val removedLiveLog = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertEquals(removedLiveBp.id, removedLiveLog.id)
        assertEquals(removedLiveBp.location.source, removedLiveLog.location.source)
        assertEquals(removedLiveBp.location.line, removedLiveLog.location.line)
        assertEquals(removedLiveBp.hitLimit, removedLiveLog.hitLimit)
    }

    @Test
    fun getMultipleLiveInstruments() {
        val origOut = System.out
        val interceptor = Interceptor(origOut)
        System.setOut(interceptor)

        //add live log
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "add-live-log",
                "integration.LiveInstrumentCLI", "4",
                "getMultipleLiveInstruments"
            )
        )
        val addedLiveLog = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertNotNull(addedLiveLog.id)
        assertEquals("getMultipleLiveInstruments", addedLiveLog.logFormat)
        assertEquals("integration.LiveInstrumentCLI", addedLiveLog.location.source)
        assertEquals(4, addedLiveLog.location.line)
        interceptor.clear()

        //add live breakpoint
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "add-live-breakpoint",
                "integration.LiveInstrumentCLI", "4",
            )
        )
        val addedLiveBp = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertNotNull(addedLiveBp.id)
        assertEquals("integration.LiveInstrumentCLI", addedLiveBp.location.source)
        assertEquals(4, addedLiveBp.location.line)
        interceptor.clear()

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
        assertEquals(2, liveInstruments.size)
        assertTrue(liveInstruments.any { it.id == addedLiveBp.id })
        assertTrue(liveInstruments.any { it.id == addedLiveLog.id })
        interceptor.clear()

        //remove live log
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "remove-live-instrument",
                addedLiveLog.id!!
            )
        )
        val removedLiveLog = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertEquals(addedLiveLog.id, removedLiveLog.id)
        assertEquals(addedLiveLog.location.source, removedLiveLog.location.source)
        assertEquals(addedLiveLog.location.line, removedLiveLog.location.line)
        interceptor.clear()

        //remove live breakpoint
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "developer",
                "remove-live-instrument",
                addedLiveBp.id!!
            )
        )
        val removedLiveBp = Json.decodeValue(interceptor.output.toString(), LiveLog::class.java)
        assertEquals(addedLiveBp.id, removedLiveBp.id)
        assertEquals(addedLiveBp.location.source, removedLiveBp.location.source)
        assertEquals(addedLiveBp.location.line, removedLiveBp.location.line)
    }
}
