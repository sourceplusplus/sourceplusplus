package spp.probe.services.instrument

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser
import spp.probe.services.common.model.Location
import spp.probe.services.instrument.model.LiveInstrument
import java.lang.instrument.Instrumentation

@RunWith(JUnit4::class)
class LiveBreakpointTest {
    companion object {
        private val parser = SpelExpressionParser(
            SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveInstrumentService::class.java.classLoader)
        )

        init {
            LiveInstrumentService.setInstrumentation(Mockito.mock(Instrumentation::class.java))
            LiveInstrumentService.setInstrumentApplier { inst: Instrumentation?, breakpoint: LiveInstrument? -> }
        }
    }

    @Test
    fun addBreakpoint() {
        LiveInstrumentService.clearAll()
        LiveInstrumentService.addBreakpoint(
            "id", "com.example.Test", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        Assert.assertEquals(1, LiveInstrumentService.getInstrumentsMap().size.toLong())
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveInstrumentService.getInstrumentsMap().size.toLong())
        val bp = LiveInstrumentService.getInstrumentsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, bp.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            bp.expression.expressionString
        )
    }

    @Test
    fun duplicateBreakpoint() {
        LiveInstrumentService.clearAll()
        val bpId = LiveInstrumentService.addBreakpoint(
            "id", "com.example.Test", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        val bpId2 = LiveInstrumentService.addBreakpoint(
            "id", "com.example.Test", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        Assert.assertEquals(bpId, bpId2)
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveInstrumentService.getInstrumentsMap().size.toLong())
        val bp = LiveInstrumentService.getInstrumentsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, bp.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            bp.expression.expressionString
        )
    }

    @Test
    fun multipleBreakpointsSameLine() {
        LiveInstrumentService.clearAll()
        val bpId = LiveInstrumentService.addBreakpoint(
            "id1", "java.lang.Object", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        val bpId2 = LiveInstrumentService.addBreakpoint(
            "id2", "java.lang.Object", 5, "1==2",
            1, 1, "SECOND", null, true
        )
        Assert.assertNotEquals(bpId, bpId2)
        Assert.assertEquals(2, LiveInstrumentService.getInstrumentsMap().size.toLong())
    }
}