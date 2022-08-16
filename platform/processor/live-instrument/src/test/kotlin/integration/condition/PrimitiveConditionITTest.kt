package integration.condition

import integration.LiveInstrumentIntegrationTest
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("UNUSED_VARIABLE", "unused")
class PrimitiveConditionITTest : LiveInstrumentIntegrationTest() {

    companion object {
        const val fieldI = 100
    }

    val instanceI = 100

    private fun primitiveStaticVariable() {
        startEntrySpan("primitiveStaticVariable")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    private fun primitiveInstanceVariable() {
        startEntrySpan("primitiveInstanceVariable")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    private fun primitiveLocalVariable() {
        startEntrySpan("primitiveLocalVariable")
        val localI = 100
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `primitive static variable`() = runBlocking {
        setupLineLabels {
            primitiveStaticVariable()
        }

        val testContext = VertxTestContext()
        onBreakpointHit {
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    PrimitiveConditionITTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    //"spp-test-probe" //todo: impl this so applyImmediately can be used
                ),
                condition = "staticFields[fieldI] == 100"
                //applyImmediately = true //todo: can't use applyImmediately
            )
        ).await()

        //trigger live breakpoint
        vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
            primitiveStaticVariable()
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun `instance local variable`() = runBlocking {
        setupLineLabels {
            primitiveInstanceVariable()
        }

        val testContext = VertxTestContext()
        onBreakpointHit {
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    PrimitiveConditionITTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    //"spp-test-probe" //todo: impl this so applyImmediately can be used
                ),
                condition = "fields[instanceI] == 100"
                //applyImmediately = true //todo: can't use applyImmediately
            )
        ).await()

        //trigger live breakpoint
        vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
            primitiveInstanceVariable()
        }

        errorOnTimeout(testContext)
    }

    @Test
    fun `primitive local variable`() = runBlocking {
        setupLineLabels {
            primitiveLocalVariable()
        }

        val testContext = VertxTestContext()
        onBreakpointHit {
            testContext.completeNow()
        }

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    PrimitiveConditionITTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    //"spp-test-probe" //todo: impl this so applyImmediately can be used
                ),
                condition = "localVariables[localI] == 100"
                //applyImmediately = true //todo: can't use applyImmediately
            )
        ).await()

        //trigger live breakpoint
        vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
            primitiveLocalVariable()
        }

        errorOnTimeout(testContext)
    }
}