/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
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
package spp.processor.live.impl

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.serviceproxy.ServiceException
import mu.KotlinLogging
import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.log.analyzer.provider.log.ILogAnalyzerService
import org.apache.skywalking.oap.log.analyzer.provider.log.LogAnalyzerServiceImpl
import org.apache.skywalking.oap.meter.analyzer.Analyzer
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.meter.analyzer.dsl.Expression
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.apache.skywalking.oap.server.core.query.MetricsQueryService
import org.apache.skywalking.oap.server.core.query.TraceQueryService
import org.apache.skywalking.oap.server.core.query.enumeration.Step
import org.apache.skywalking.oap.server.core.query.input.Duration
import org.apache.skywalking.oap.server.core.query.input.Entity
import org.apache.skywalking.oap.server.core.query.input.MetricsCondition
import org.apache.skywalking.oap.server.core.query.type.KVInt
import org.apache.skywalking.oap.server.core.query.type.Ref
import org.apache.skywalking.oap.server.core.query.type.Span
import org.apache.skywalking.oap.server.core.version.Version
import org.joor.Reflect
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.platform.common.FeedbackProcessor
import spp.platform.common.util.args
import spp.processor.ViewProcessor
import spp.processor.live.impl.view.LiveLogView
import spp.processor.live.impl.view.LiveMeterView
import spp.processor.live.impl.view.LiveTraceView
import spp.processor.live.impl.view.util.EntitySubscribersCache
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.processor.live.impl.view.util.ViewSubscriber
import spp.protocol.artifact.metrics.MetricStep
import spp.protocol.artifact.metrics.MetricType
import spp.protocol.artifact.trace.TraceSpan
import spp.protocol.artifact.trace.TraceSpanRef
import spp.protocol.artifact.trace.TraceStack
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.service.LiveViewService
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.service.SourceServices.Subscribe.toLiveViewSubscription
import spp.protocol.service.error.RuleAlreadyExistsException
import spp.protocol.view.HistoricalView
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewEvent
import spp.protocol.view.rule.ViewRule
import java.time.Instant
import java.util.*

@Suppress("TooManyFunctions") // public API
class LiveViewServiceImpl : CoroutineVerticle(), LiveViewService {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    internal lateinit var meterSystem: MeterSystem
    internal lateinit var meterProcessService: MeterProcessService
    internal lateinit var metricsQuery: MetricsQueryService
    internal lateinit var traceQuery: TraceQueryService

    //todo: use ExpiringSharedData
    private val subscriptionCache = MetricTypeSubscriptionCache()
    val meterView = LiveMeterView(subscriptionCache)
    val traceView = LiveTraceView(subscriptionCache)
    val logView = LiveLogView(subscriptionCache)
    internal lateinit var skywalkingVersion: String

    override suspend fun start() {
        log.debug("Starting LiveViewServiceImpl")
        skywalkingVersion = Version.CURRENT.buildVersion
        FeedbackProcessor.module!!.find(CoreModule.NAME).provider().apply {
            meterSystem = getService(MeterSystem::class.java)
        }
        FeedbackProcessor.module!!.find(AnalyzerModule.NAME).provider().apply {
            meterProcessService = getService(IMeterProcessService::class.java) as MeterProcessService
        }
        FeedbackProcessor.module!!.find(CoreModule.NAME).provider().apply {
            metricsQuery = getService(MetricsQueryService::class.java) as MetricsQueryService
        }
        FeedbackProcessor.module!!.find(CoreModule.NAME).provider().apply {
            traceQuery = getService(TraceQueryService::class.java) as TraceQueryService
        }

        //load preset view rules
        val livePresets = ClusterConnection.config.getJsonObject("live-presets") ?: JsonObject()
        livePresets.map.keys.forEach {
            val presetName = it
            val preset = livePresets.getJsonObject(presetName)
            if (preset.getString("enabled").toBooleanStrict()) {
                val viewRules = preset.getJsonArray("view-rules", JsonArray())
                Vertx.currentContext().putLocal("developer", DeveloperAuth("system"))
                viewRules.forEach {
                    val viewRule = ViewRule(JsonObject.mapFrom(it))
                    saveRuleIfAbsent(viewRule).await()
                }
                Vertx.currentContext().removeLocal("developer")
                if (viewRules.size() > 0) {
                    log.info { "Loaded ${viewRules.size()} live view rules from preset '$presetName'" }
                }
            }
        }

        //live traces view
        val segmentParserService = FeedbackProcessor.module!!.find(AnalyzerModule.NAME)
            .provider().getService(ISegmentParserService::class.java) as SegmentParserServiceImpl
        val listenerManagerField = segmentParserService.javaClass.getDeclaredField("listenerManager")
        listenerManagerField.trySetAccessible()
        val listenerManager = listenerManagerField.get(segmentParserService) as SegmentParserListenerManager
        listenerManager.add(ViewProcessor.liveViewService.traceView)

        //live logs view
        val logParserService = FeedbackProcessor.module!!.find(LogAnalyzerModule.NAME)
            .provider().getService(ILogAnalyzerService::class.java) as LogAnalyzerServiceImpl
        logParserService.addListenerFactory(ViewProcessor.liveViewService.logView)

        vertx.eventBus().consumer<JsonObject>(MARKER_DISCONNECTED) {
            val devAuth = DeveloperAuth.from(it.body())
            clearLiveViews(devAuth.selfId).onComplete {
                if (it.succeeded()) {
                    log.info("Cleared live views for disconnected marker: {}", devAuth.selfId)
                } else {
                    log.error("Failed to clear live views on marker disconnection", it.cause())
                }
            }
        }
    }

    override fun saveRule(rule: ViewRule): Future<ViewRule> {
        //check for existing rule
        var sppAnalyzers: MutableList<Analyzer>? = null
        val exitingRule = meterProcessService.converts().any { ruleset ->
            val analyzers = Reflect.on(ruleset).get<MutableList<Analyzer>>("analyzers")
            analyzers.any {
                val metricName = Reflect.on(it).get<String>("metricName")
                if (metricName.startsWith("spp_")) {
                    sppAnalyzers = analyzers
                }
                metricName == "spp_" + rule.name
            }
        }
        if (exitingRule) {
            return Future.failedFuture(RuleAlreadyExistsException("Rule with name ${rule.name} already exists"))
        }

        val meterConfig = MeterConfig()
        meterConfig.metricPrefix = "spp"
        meterConfig.metricsRules = listOf(
            MeterConfig.Rule().apply {
                name = rule.name
                exp = rule.exp
            }
        )
        if (sppAnalyzers == null) {
            //create spp ruleset
            try {
                meterProcessService.converts().add(MetricConvert(meterConfig, meterSystem))
            } catch (e: Exception) {
                return Future.failedFuture(e)
            }
        } else {
            //add rule to existing spp ruleset
            val newConvert = try {
                MetricConvert(meterConfig, meterSystem)
            } catch (e: Exception) {
                return Future.failedFuture(e)
            }

            val newAnalyzer = Reflect.on(newConvert).get<List<Analyzer>>("analyzers").first()
            sppAnalyzers!!.add(newAnalyzer)
        }

        return Future.succeededFuture(rule)
    }

    override fun deleteRule(ruleName: String): Future<ViewRule?> {
        var removedRule: ViewRule? = null
        (meterProcessService.converts() as MutableList<MetricConvert>).removeIf {
            val analyzers = Reflect.on(it).get<MutableList<Analyzer>>("analyzers")
            analyzers.removeIf {
                val metricName = Reflect.on(it).get<String>("metricName")
                val remove = metricName == ruleName || metricName == "spp_$ruleName"
                if (remove) {
                    val expression = Reflect.on(it).get<Expression>("expression")
                    removedRule = ViewRule(metricName, Reflect.on(expression).get("literal"))
                }
                remove
            }
            analyzers.isEmpty()
        }
        return Future.succeededFuture(removedRule)
    }

    override fun addLiveView(subscription: LiveView): Future<LiveView> {
        val promise = Promise.promise<LiveView>()
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        val address = "view." + UUID.randomUUID().toString()
        val sub = subscription.copy(subscriptionId = address)
        log.debug("Adding live view: {}", sub)

        val consumer = vertx.eventBus().consumer<JsonObject>(address)
        consumer.handler {
            val event = it.body()
            val viewEvent = if (event.getBoolean("multiMetrics")) {
                val events = event.getJsonArray("metrics")
                val firstEvent = events.getJsonObject(0)
                LiveViewEvent(
                    sub.subscriptionId!!,
                    sub.entityIds.first(),
                    sub.artifactQualifiedName,
                    firstEvent.getString("timeBucket"),
                    sub.viewConfig,
                    events.toString()
                )
            } else {
                LiveViewEvent(
                    sub.subscriptionId!!,
                    sub.entityIds.first(),
                    sub.artifactQualifiedName,
                    event.getString("timeBucket"),
                    sub.viewConfig,
                    event.toString()
                )
            }

            //publish view event to subscribers
            val eventJson = JsonObject.mapFrom(viewEvent)
            vertx.eventBus().publish(toLiveViewSubscription(sub.subscriptionId!!), eventJson)
            vertx.eventBus().publish(toLiveViewSubscriberAddress(devAuth.selfId), eventJson)
        }.completionHandler {
            if (it.succeeded()) {
                val subscriber = ViewSubscriber(
                    sub,
                    devAuth.selfId,
                    System.currentTimeMillis(),
                    mutableMapOf(),
                    consumer
                )

                sub.viewConfig.viewMetrics.forEach {
                    subscriptionCache.computeIfAbsent(it) { EntitySubscribersCache() }
                    sub.entityIds.forEach { entityId ->
                        subscriptionCache[it]!!.computeIfAbsent(entityId) { mutableSetOf() }
                        (subscriptionCache[it]!![entityId]!! as MutableSet).add(subscriber)
                    }
                }

                promise.complete(sub)
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    override fun removeLiveView(id: String): Future<LiveView> {
        log.debug { "Removing live view: {}".args(id) }
        val promise = Promise.promise<LiveView>()
        var unsubbedUser: ViewSubscriber? = null
        subscriptionCache.flatMap { it.value.values }.forEach { subList ->
            val subscription = subList.firstOrNull { it.subscription.subscriptionId == id }
            if (subscription != null) {
                (subList as MutableSet).remove(subscription)
                unsubbedUser = subscription
            }
        }

        if (unsubbedUser != null) {
            unsubbedUser!!.consumer.unregister {
                if (it.succeeded()) {
                    promise.complete(
                        LiveView(
                            unsubbedUser!!.subscription.subscriptionId,
                            unsubbedUser!!.subscription.entityIds,
                            unsubbedUser!!.subscription.artifactQualifiedName,
                            unsubbedUser!!.subscription.artifactLocation,
                            unsubbedUser!!.subscription.viewConfig
                        )
                    )
                } else {
                    promise.fail(it.cause())
                }
            }
        } else {
            promise.fail("Invalid subscription id")
        }
        return promise.future()
    }

    override fun updateLiveView(id: String, subscription: LiveView): Future<LiveView> {
        log.debug { "Updating live view: {}".args(id) }
        val promise = Promise.promise<LiveView>()

        var viewSubscriber: ViewSubscriber? = null
        subscriptionCache.forEach {
            it.value.forEach {
                it.value.forEach {
                    if (it.subscription.subscriptionId == id) {
                        viewSubscriber = it
                    }
                }
            }
        }

        if (viewSubscriber != null) {
            val removedEntityIds = viewSubscriber!!.subscription.entityIds - subscription.entityIds
            val addedEntityIds = subscription.entityIds - viewSubscriber!!.subscription.entityIds
            viewSubscriber!!.subscription.entityIds.removeAll(removedEntityIds)
            viewSubscriber!!.subscription.entityIds.addAll(addedEntityIds)

            subscription.viewConfig.viewMetrics.forEach {
                subscriptionCache.computeIfAbsent(it) { EntitySubscribersCache() }

                removedEntityIds.forEach { entityId ->
                    val entityIdMetrics = subscriptionCache[it]!![entityId]!! as MutableSet
                    entityIdMetrics.removeIf {
                        it.subscription.subscriptionId == id
                    }
                    if (entityIdMetrics.isEmpty()) {
                        subscriptionCache[it]!!.remove(entityId)
                    }
                }
                addedEntityIds.forEach { entityId ->
                    subscriptionCache[it]!!.computeIfAbsent(entityId) { mutableSetOf() }
                    (subscriptionCache[it]!![entityId]!! as MutableSet).add(viewSubscriber!!)
                }
            }

            promise.complete(subscription)
        } else {
            promise.fail("Invalid subscription id")
        }

        return promise.future()
    }

    override fun getLiveView(id: String): Future<LiveView> {
        log.debug { "Getting live view: {}".args(id) }
        val promise = Promise.promise<LiveView>()
        var subbedUser: ViewSubscriber? = null
        subscriptionCache.flatMap { it.value.values }.forEach { subList ->
            val subscription = subList.firstOrNull { it.subscription.subscriptionId == id }
            if (subscription != null) {
                subbedUser = subscription
            }
        }

        if (subbedUser != null) {
            promise.complete(
                LiveView(
                    subbedUser!!.subscription.subscriptionId,
                    subbedUser!!.subscription.entityIds,
                    subbedUser!!.subscription.artifactQualifiedName,
                    subbedUser!!.subscription.artifactLocation,
                    subbedUser!!.subscription.viewConfig
                )
            )
        } else {
            promise.fail("Invalid subscription id")
        }
        return promise.future()
    }

    override fun getLiveViews(): Future<List<LiveView>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        val viewSubscriptions = mutableSetOf<LiveView>()
        subscriptionCache.forEach {
            it.value.forEach {
                it.value.forEach {
                    if (it.subscriberId == devAuth.selfId) {
                        viewSubscriptions.add(it.subscription)
                    }
                }
            }
        }
        return Future.succeededFuture(viewSubscriptions.toList())
    }

    override fun clearLiveViews(): Future<List<LiveView>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        return clearLiveViews(devAuth.selfId)
    }

    private fun clearLiveViews(subscriberId: String): Future<List<LiveView>> {
        val promise = Promise.promise<List<LiveView>>()
        val removedSubs = mutableSetOf<ViewSubscriber>()
        subscriptionCache.entries.removeIf {
            it.value.entries.removeIf {
                (it.value as MutableSet).removeIf {
                    if (it.subscriberId == subscriberId) {
                        removedSubs.add(it)
                        true
                    } else false
                }
                it.value.isEmpty()
            }
            it.value.isEmpty()
        }
        if (removedSubs.isNotEmpty()) {
            removedSubs.forEach {
                it.consumer.unregister()
            }
            promise.complete(removedSubs.map { it.subscription })
        } else {
            promise.complete(emptyList())
        }
        return promise.future()
    }

    override fun getLiveViewStats(): Future<JsonObject> {
        val subStats = JsonObject()
        subscriptionCache.forEach { type ->
            subStats.put(type.key, JsonObject())
            type.value.forEach { key, value ->
                subStats.getJsonObject(type.key).put(key, value.size)
            }
        }
        return Future.succeededFuture(subStats)
    }

    override fun getHistoricalMetrics(
        entityIds: List<String>,
        metricIds: List<String>,
        step: MetricStep,
        start: Instant,
        stop: Instant?
    ): Future<HistoricalView> {
        return vertx.executeBlocking({
            try {
                val historicalView = HistoricalView(entityIds, metricIds)
                val entityId = entityIds.first()
                val duration = Duration().apply {
                    this.start = step.formatter.format(start)
                    this.end = step.formatter.format(stop ?: Instant.now())
                    this.step = Step.valueOf(step.name)
                }

                metricIds.forEach { metricId ->
                    val metricType = MetricType(metricId).asHistorical()
                    val values = getHistoricalMetrics(metricType, entityId, duration)
                    values.forEachIndexed { i, metric ->
                        val stepMetrics = JsonObject()
                            .put("metricId", metricId)
                            .put("value", metric.value)
                        historicalView.data.add(stepMetrics)
                    }
                }

                it.complete(historicalView)
            } catch (ex: Exception) {
                ex.printStackTrace()
                it.fail(ServiceException(500, ex.message))
            }
        }, false)
    }

    override fun getTraceStack(traceId: String): Future<TraceStack?> {
        return vertx.executeBlocking {
            val trace = traceQuery.queryTrace(traceId)
            if (trace.spans.isEmpty()) {
                it.complete(null)
            } else {
                val traceStack = TraceStack(trace.spans.map { it.toProtocol() })
                it.complete(traceStack)
            }
        }
    }

    private fun getHistoricalMetrics(metricType: MetricType, entityId: String, duration: Duration): List<KVInt> {
        val condition = MetricsCondition()
        condition.name = metricType.metricId
        condition.entity = object : Entity() {
            override fun buildId(): String = entityId
            override fun isValid(): Boolean = true
        }
        return Reflect.on(metricsQuery.readMetricsValues(condition, duration).values).get("values")
    }

    private fun Span.toProtocol(): TraceSpan {
        return TraceSpan(
            traceId = traceId,
            segmentId = segmentId,
            spanId = spanId,
            parentSpanId = parentSpanId,
            refs = refs.map { it.toProtocol() },
            serviceCode = serviceCode,
            //serviceInstanceName = serviceInstanceName, //todo: this
            startTime = Instant.ofEpochMilli(startTime),
            endTime = Instant.ofEpochMilli(endTime),
            endpointName = endpointName,
            type = type,
            peer = peer,
            component = component,
            error = isError,
            layer = layer,
            tags = tags.associate { it.key to it.value!! },
//            logs = logs.map { it.toProtocol() }
        )
    }

    private fun Ref.toProtocol(): TraceSpanRef {
        return TraceSpanRef(
            traceId = traceId,
            parentSegmentId = parentSegmentId,
            parentSpanId = parentSpanId,
            type = type.name
        )
    }
}
