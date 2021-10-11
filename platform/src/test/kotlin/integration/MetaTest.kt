package integration

import com.sourceplusplus.protocol.SourceMarkerServices
import com.sourceplusplus.protocol.instrument.LiveSourceLocation
import com.sourceplusplus.protocol.instrument.breakpoint.LiveBreakpoint
import com.sourceplusplus.protocol.service.live.LiveInstrumentService
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.serviceproxy.ServiceProxyBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class MetaTest : IntegrationTest() {

    @Test
    fun multipleMetaAttributes() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("MetaTest", 42),
                meta = mutableMapOf("key1" to "value1", "key2" to "value2")
            )
        ) {
            if (it.succeeded()) {
                testContext.verify {
                    assertNotNull(it.result())
                    val instrument = it.result()!!
                    assertEquals(2, instrument.meta.size)
                    assertEquals(instrument.meta["key1"], "value1")
                    assertEquals(instrument.meta["key2"], "value2")
                }

                instrumentService.removeLiveInstrument(it.result().id!!) {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Test
    fun getInstrumentsWithMeta() {
        val testContext = VertxTestContext()
        val instrumentService = ServiceProxyBuilder(vertx)
            .setToken(SYSTEM_JWT_TOKEN)
            .setAddress(SourceMarkerServices.Utilize.LIVE_INSTRUMENT)
            .build(LiveInstrumentService::class.java)

        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("MetaTest", 42),
                meta = mutableMapOf("key1" to "value1", "key2" to "value2")
            )
        ) {
            if (it.succeeded()) {
                instrumentService.getLiveInstruments {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(1, it.result().size)
                            val instrument = it.result()[0]
                            assertEquals(2, instrument.meta.size)
                            assertEquals(instrument.meta["key1"], "value1")
                            assertEquals(instrument.meta["key2"], "value2")
                        }
                        instrumentService.removeLiveInstrument(it.result()[0].id!!) {
                            if (it.succeeded()) {
                                testContext.completeNow()
                            } else {
                                testContext.failNow(it.cause())
                            }
                        }
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
