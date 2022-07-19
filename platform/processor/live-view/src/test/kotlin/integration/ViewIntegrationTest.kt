///*
// * Source++, the open-source live coding platform.
// * Copyright (C) 2022 CodeBrig, Inc.
// *
// * This program is free software: you can redistribute it and/or modify
// * it under the terms of the GNU Affero General Public License as published
// * by the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * This program is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU Affero General Public License for more details.
// *
// * You should have received a copy of the GNU Affero General Public License
// * along with this program.  If not, see <https://www.gnu.org/licenses/>.
// */
//package integration
//
//import io.vertx.core.json.Json
//import io.vertx.core.json.JsonObject
//import io.vertx.junit5.VertxExtension
//import io.vertx.junit5.VertxTestContext
//import io.vertx.kotlin.coroutines.await
//import io.vertx.serviceproxy.ServiceProxyBuilder
//import kotlinx.coroutines.GlobalScope
//import kotlinx.coroutines.launch
//import org.junit.jupiter.api.Test
//import org.junit.jupiter.api.extension.ExtendWith
//import org.slf4j.LoggerFactory
//import spp.protocol.SourceServices
//import spp.protocol.SourceServices.Provide.toLiveViewSubscriberAddress
//import spp.protocol.artifact.ArtifactQualifiedName
//import spp.protocol.artifact.ArtifactType
//import spp.protocol.instrument.LiveMeter
//import spp.protocol.instrument.LiveSourceLocation
//import spp.protocol.instrument.meter.MeterType
//import spp.protocol.instrument.meter.MetricValue
//import spp.protocol.instrument.meter.MetricValueType
//import spp.protocol.service.LiveInstrumentService
//import spp.protocol.service.LiveViewService
//import spp.protocol.view.LiveViewConfig
//import spp.protocol.view.LiveViewEvent
//import spp.protocol.view.LiveViewSubscription
//import java.util.concurrent.TimeUnit
//
//@ExtendWith(VertxExtension::class)
//class ViewIntegrationTest : ProcessorIntegrationTest() {
//
//    companion object {
//        private val log = LoggerFactory.getLogger(ViewIntegrationTest::class.java)
//    }
//
//    @Test
//    fun verifyLiveView() {
//        val testContext = VertxTestContext()
//
//        val consumer = vertx.eventBus().localConsumer<JsonObject>(toLiveViewSubscriberAddress("system"))
//        consumer.handler {
//            log.info("Got view event: {}", it.body())
//            val event = Json.decodeValue(it.body().toString(), LiveViewEvent::class.java)
//            val metricsData = JsonObject(event.metricsData)
//            if (metricsData.getInteger("last_minute") > 0) {
//                consumer.unregister()
//                testContext.completeNow()
//            }
//        }.completionHandler {
//            if (it.failed()) {
//                testContext.failNow(it.cause())
//                return@completionHandler
//            }
//
//            val instrumentService = ServiceProxyBuilder(vertx)
//                .setToken(SYSTEM_JWT_TOKEN)
//                .setAddress(SourceServices.Utilize.LIVE_INSTRUMENT)
//                .build(LiveInstrumentService::class.java)
//
//            GlobalScope.launch {
//                val liveMeter = instrumentService.addLiveInstrument(
//                    LiveMeter(
//                        "Test counter",
//                        MeterType.COUNT,
//                        MetricValue(
//                            MetricValueType.NUMBER,
//                            "1"
//                        ),
//                        location = LiveSourceLocation("E2EApp", 24),
//                        applyImmediately = true
//                    )
//                ).await() as LiveMeter
//                val viewService = ServiceProxyBuilder(vertx)
//                    .setToken(SYSTEM_JWT_TOKEN)
//                    .setAddress(SourceServices.Utilize.LIVE_VIEW)
//                    .build(LiveViewService::class.java)
//
//                viewService.addLiveViewSubscription(
//                    LiveViewSubscription(
//                        null,
//                        listOf(liveMeter.toMetricId()),
//                        ArtifactQualifiedName(liveMeter.location.source, type = ArtifactType.EXPRESSION),
//                        liveMeter.location,
//                        LiveViewConfig("LIVE_METER", listOf("last_minute", "last_hour", "last_day"))
//                    )
//                ).await()
//            }
//        }
//
//        if (testContext.awaitCompletion(130, TimeUnit.SECONDS)) {
//            if (testContext.failed()) {
//                consumer.unregister()
//                throw testContext.causeOfFailure()
//            }
//        } else {
//            consumer.unregister()
//            throw RuntimeException("Test timed out")
//        }
//    }
//}
