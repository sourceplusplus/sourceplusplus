/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.processor.live.impl.view

import io.vertx.core.*
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.datetime.*
import org.apache.skywalking.oap.server.core.exporter.ExportEvent
import org.apache.skywalking.oap.server.core.exporter.MetricValuesExportService
import org.apache.skywalking.oap.server.core.query.enumeration.Scope
import org.apache.skywalking.oap.server.core.query.enumeration.Step
import org.apache.skywalking.oap.server.core.query.input.Duration
import org.apache.skywalking.oap.server.core.query.input.Entity
import org.apache.skywalking.oap.server.core.query.input.MetricsCondition
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection
import spp.platform.common.extend.getMeterServiceInstances
import spp.platform.common.extend.getMeterServices
import spp.processor.ViewProcessor.metadata
import spp.processor.ViewProcessor.metricsQueryService
import spp.processor.live.impl.view.util.EntitySubscribersCache
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.protocol.instrument.DurationStep
import spp.protocol.instrument.LiveSourceLocation
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class LiveMeterView(
    private val subscriptionCache: MetricTypeSubscriptionCache
) : MetricValuesExportService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveMeterView::class.java)
    }

    override fun export(event: ExportEvent) {
        if (event.type == ExportEvent.EventType.INCREMENT) return
        val metricName = event.metrics.javaClass.simpleName
        if (!metricName.startsWith("spp_")) return
        if (log.isTraceEnabled) log.trace("Processing exported meter event: {}", event)

        val subbedMetrics = subscriptionCache[metricName]
        if (subbedMetrics != null) {
            //todo: location should be coming from subscription, as is functions as service/serviceInstance wildcard
            val location = LiveSourceLocation("", 0)
            sendMeterEvent(metricName, location, subbedMetrics, event.metrics.timeBucket)
        }
    }

    fun sendMeterEvent(
        metricName: String,
        location: LiveSourceLocation,
        subbedMetrics: EntitySubscribersCache,
        timeBucket: Long
    ) {
        val metricFutures = mutableListOf<Future<JsonObject>>()
        val minutePromise = Promise.promise<JsonObject>()
        getLiveMeterMetrics(
            metricName,
            location,
            Clock.System.now().minus(1, DateTimeUnit.MINUTE),
            Clock.System.now(),
            DurationStep.MINUTE,
            minutePromise
        )
        metricFutures.add(minutePromise.future())

        val hourPromise = Promise.promise<JsonObject>()
        getLiveMeterMetrics(
            metricName,
            location,
            Clock.System.now().minus(1, DateTimeUnit.HOUR),
            Clock.System.now(),
            DurationStep.HOUR,
            hourPromise
        )
        metricFutures.add(hourPromise.future())

        val dayPromise = Promise.promise<JsonObject>()
        getLiveMeterMetrics(
            metricName,
            location,
            Clock.System.now().minus(24, DateTimeUnit.HOUR),
            Clock.System.now(),
            DurationStep.DAY,
            dayPromise
        )
        metricFutures.add(dayPromise.future())

        CompositeFuture.all(metricFutures as List<Future<JsonObject>>).onComplete {
            if (it.succeeded()) {
                val minute = minutePromise.future().result()
                val hour = hourPromise.future().result()
                val day = dayPromise.future().result()

                subbedMetrics.values.flatten().forEach {
                    ClusterConnection.getVertx().eventBus().send(
                        it.consumer.address(),
                        JsonObject()
                            .put("last_minute", minute.getJsonArray("values").firstOrNull() ?: 0) //todo: seems to want last
                            .put("last_hour", hour.getJsonArray("values").firstOrNull() ?: 0)
                            .put("last_day", day.getJsonArray("values").firstOrNull() ?: 0)
                            .put("timeBucket", timeBucket)
                            .put("multiMetrics", false)
                    )
                }
            } else {
                log.error("Failed to get live meter metrics", it.cause())
            }
        }
    }

    //todo: taken from LiveInstrumentProcessorImpl, should be moved to common service
    fun getLiveMeterMetrics(
        metricId: String,
        location: LiveSourceLocation,
        start: Instant,
        stop: Instant,
        step: DurationStep,
        handler: Handler<AsyncResult<JsonObject>>
    ) {
        log.debug("Getting live meter metrics. Metric id: {}", metricId)
        val services = metadata.getMeterServices(location.service ?: "")
        if (services.isEmpty()) {
            log.info("No services found")
            handler.handle(Future.succeededFuture(JsonObject().put("values", JsonArray())))
            return
        }

        val values = mutableListOf<Any>()
        services.forEach { service ->
            val instances = metadata.getMeterServiceInstances(
                start.toEpochMilliseconds(), stop.toEpochMilliseconds(), service.id
            )
            if (instances.isEmpty()) {
                log.info("No instances found for service: ${service.id}")
                return@forEach
            }

            instances.forEach { instance ->
                val serviceInstance = location.serviceInstance
                if (serviceInstance != null && serviceInstance != instance.name) {
                    return@forEach
                }

                val condition = MetricsCondition().apply {
                    name = metricId
                    entity = Entity().apply {
                        setScope(Scope.ServiceInstance)
                        setNormal(true)
                        setServiceName(service.name)
                        setServiceInstanceName(instance.name)
                    }
                }
                if (metricId.contains("histogram")) {
                    val value = metricsQueryService.readHeatMap(condition, Duration().apply {
                        Reflect.on(this).set(
                            "start",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(start.toJavaInstant())
                        )
                        Reflect.on(this).set(
                            "end",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(stop.toJavaInstant())
                        )
                        Reflect.on(this).set("step", Step.valueOf(step.name))
                    })
                    values.add(value)
                } else {
                    val value = metricsQueryService.readMetricsValue(condition, Duration().apply {
                        Reflect.on(this).set(
                            "start",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(start.toJavaInstant())
                        )
                        Reflect.on(this).set(
                            "end",
                            DateTimeFormatter.ofPattern(step.pattern).withZone(ZoneOffset.UTC)
                                .format(stop.toJavaInstant())
                        )
                        Reflect.on(this).set("step", Step.valueOf(step.name))
                    })
                    values.add(value)
                }
            }
        }
        handler.handle(Future.succeededFuture(JsonObject().put("values", JsonArray(values))))
    }
}
