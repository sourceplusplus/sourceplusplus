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
class LiveLogTest {
    companion object {
        private val parser = SpelExpressionParser(
            SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveInstrumentService::class.java.classLoader)
        )

        init {
            LiveInstrumentService.setInstrumentation(Mockito.mock(Instrumentation::class.java))
            LiveInstrumentService.setInstrumentApplier { _: Instrumentation?, _: LiveInstrument? -> }
            LiveInstrumentService.setInstrumentEventConsumer { _, _ -> }
        }
    }

    @Test
    fun addLog() {
        LiveInstrumentService.clearAll()
        LiveInstrumentService.addLog(
            "id", "test", arrayOfNulls(0), "com.example.Test", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        Assert.assertEquals(1, LiveInstrumentService.getInstrumentsMap().size.toLong())
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveInstrumentService.getInstrumentsMap().size.toLong())
        val log = LiveInstrumentService.getInstrumentsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, log.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            log.expression.expressionString
        )
    }

    @Test
    fun duplicateLog() {
        LiveInstrumentService.clearAll()
        val logId = LiveInstrumentService.addLog(
            "id", "test", arrayOfNulls(0), "com.example.Test", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        val logId2 = LiveInstrumentService.addLog(
            "id", "test", arrayOfNulls(0), "com.example.Test", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        Assert.assertEquals(logId, logId2)
        val location = Location("com.example.Test", 5)
        Assert.assertEquals(1, LiveInstrumentService.getInstrumentsMap().size.toLong())
        val log = LiveInstrumentService.getInstrumentsMap().values.stream().findFirst().get()
        Assert.assertEquals(location, log.location)
        Assert.assertEquals(
            parser.parseExpression("1==1").expressionString,
            log.expression.expressionString
        )
    }

    @Test
    fun multipleLogsSameLine() {
        LiveInstrumentService.clearAll()
        val logId = LiveInstrumentService.addLog(
            "id1", "test", arrayOfNulls(0), "java.lang.Object", 5,
            "1==1", 1, 1, "SECOND", null, true
        )
        val logId2 = LiveInstrumentService.addLog(
            "id2", "test", arrayOfNulls(0), "java.lang.Object", 5,
            "1==2", 1, 1, "SECOND", null, true
        )
        Assert.assertNotEquals(logId, logId2)
        Assert.assertEquals(2, LiveInstrumentService.getInstrumentsMap().size.toLong())
    }
}