package spp.probe.services.impl.breakpoint

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser
import spp.probe.services.common.model.Location
import spp.probe.services.impl.breakpoint.model.LiveBreakpoint
import java.lang.instrument.Instrumentation

@RunWith(JUnit4::class)
class LiveBreakpointServiceTest {
    companion object {
        private val parser = SpelExpressionParser(
            SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveBreakpointService::class.java.classLoader)
        )

        init {
            LiveBreakpointService.setInstrumentation(Mockito.mock(Instrumentation::class.java))
            LiveBreakpointService.setBreakpointApplier { inst: Instrumentation?, breakpoint: LiveBreakpoint? -> }
        }
    }

    @Test
    fun addBreakpoint() {
        LiveBreakpointService.clearAll()
        LiveBreakpointService.addBreakpoint(
            "id", "com.example.Test", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        Assert.assertEquals(1, LiveBreakpointService.getBreakpointsMap().size.toLong())
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveBreakpointService.getBreakpointsMap().size.toLong())
        val bp = LiveBreakpointService.getBreakpointsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, bp.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            bp.expression.expressionString
        )
    }

    @Test
    fun duplicateBreakpoint() {
        LiveBreakpointService.clearAll()
        val bpId = LiveBreakpointService.addBreakpoint(
            "id", "com.example.Test", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        val bpId2 = LiveBreakpointService.addBreakpoint(
            "id", "com.example.Test", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        Assert.assertEquals(bpId, bpId2)
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveBreakpointService.getBreakpointsMap().size.toLong())
        val bp = LiveBreakpointService.getBreakpointsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, bp.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            bp.expression.expressionString
        )
    }

    @Test
    fun multipleBreakpointsSameLine() {
        LiveBreakpointService.clearAll()
        val bpId = LiveBreakpointService.addBreakpoint(
            "id1", "java.lang.Object", 5, "1==1",
            1, 1, "SECOND", null, true
        )
        val bpId2 = LiveBreakpointService.addBreakpoint(
            "id2", "java.lang.Object", 5, "1==2",
            1, 1, "SECOND", null, true
        )
        Assert.assertNotEquals(bpId, bpId2)
        Assert.assertEquals(2, LiveBreakpointService.getBreakpointsMap().size.toLong())
    }
}