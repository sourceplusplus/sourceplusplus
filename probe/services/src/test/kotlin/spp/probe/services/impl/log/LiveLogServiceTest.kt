package spp.probe.services.impl.log

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import org.springframework.expression.spel.SpelCompilerMode
import org.springframework.expression.spel.SpelParserConfiguration
import org.springframework.expression.spel.standard.SpelExpressionParser
import spp.probe.services.common.model.Location
import spp.probe.services.impl.log.model.LiveLog
import java.lang.instrument.Instrumentation

@RunWith(JUnit4::class)
class LiveLogServiceTest {
    companion object {
        private val parser = SpelExpressionParser(
            SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveLogService::class.java.classLoader)
        )

        init {
            LiveLogService.setInstrumentation(Mockito.mock(Instrumentation::class.java))
            LiveLogService.setLogApplier { inst: Instrumentation?, log: LiveLog? -> }
        }
    }

    @Test
    fun addLog() {
        LiveLogService.clearAll()
        LiveLogService.addLog(
            "id", "test", arrayOfNulls(0), "com.example.Test", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        Assert.assertEquals(1, LiveLogService.getLogsMap().size.toLong())
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveLogService.getLogsMap().size.toLong())
        val bp = LiveLogService.getLogsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, bp.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            bp.expression.expressionString
        )
    }

    @Test
    fun duplicateLog() {
        LiveLogService.clearAll()
        val bpId = LiveLogService.addLog(
            "id", "test", arrayOfNulls(0), "com.example.Test", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        val bpId2 = LiveLogService.addLog(
            "id", "test", arrayOfNulls(0), "com.example.Test", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        Assert.assertEquals(bpId, bpId2)
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveLogService.getLogsMap().size.toLong())
        val bp = LiveLogService.getLogsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, bp.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            bp.expression.expressionString
        )
    }

    @Test
    fun multipleLogsSameLine() {
        LiveLogService.clearAll()
        val bpId = LiveLogService.addLog(
            "id1", "test", arrayOfNulls(0), "java.lang.Object", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        val bpId2 = LiveLogService.addLog(
            "id2", "test", arrayOfNulls(0), "java.lang.Object", 5,
            "1==2", 1, 1, "SECOND", null, true
        )
        Assert.assertNotEquals(bpId, bpId2)
        Assert.assertEquals(2, LiveLogService.getLogsMap().size.toLong())
    }
}