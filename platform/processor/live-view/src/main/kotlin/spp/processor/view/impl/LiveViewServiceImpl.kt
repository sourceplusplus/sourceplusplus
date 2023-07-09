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
package spp.processor.view.impl

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.serviceproxy.ServiceException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.log.analyzer.provider.log.ILogAnalyzerService
import org.apache.skywalking.oap.log.analyzer.provider.log.LogAnalyzerServiceImpl
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.apache.skywalking.oap.server.core.query.AggregationQueryService
import org.apache.skywalking.oap.server.core.query.MetricsQueryService
import org.apache.skywalking.oap.server.core.query.TraceQueryService
import org.apache.skywalking.oap.server.core.query.enumeration.Step
import org.apache.skywalking.oap.server.core.query.input.Duration
import org.apache.skywalking.oap.server.core.query.input.Entity
import org.apache.skywalking.oap.server.core.query.input.MetricsCondition
import org.apache.skywalking.oap.server.core.query.input.TopNCondition
import org.apache.skywalking.oap.server.core.query.type.Ref
import org.apache.skywalking.oap.server.core.query.type.Span
import org.apache.skywalking.oap.server.core.storage.annotation.ValueColumnMetadata
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.platform.common.FeedbackProcessor
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.processor.view.ViewProcessor
import spp.processor.view.impl.view.LiveLogView
import spp.processor.view.impl.view.LiveMeterView
import spp.processor.view.impl.view.LiveTraceView
import spp.processor.view.impl.view.util.EntitySubscribersCache
import spp.processor.view.impl.view.util.MetricTypeSubscriptionCache
import spp.processor.view.model.LiveMeterConfig
import spp.processor.view.model.LiveMetricConvert
import spp.processor.view.model.LiveMetricConvert.Companion.NOP_RULE
import spp.processor.view.model.ViewSubscriber
import spp.protocol.artifact.metrics.MetricStep
import spp.protocol.artifact.metrics.MetricType
import spp.protocol.artifact.trace.TraceSpan
import spp.protocol.artifact.trace.TraceSpanRef
import spp.protocol.artifact.trace.TraceStack
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.platform.general.Order
import spp.protocol.platform.general.Scope
import spp.protocol.platform.general.SelectedRecord
import spp.protocol.platform.general.Service
import spp.protocol.service.LiveManagementService
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
import org.apache.skywalking.oap.server.core.query.enumeration.Order as SkyWalkingOrder
import org.apache.skywalking.oap.server.core.query.enumeration.Scope as SkyWalkingScope

@Suppress("TooManyFunctions") // public API
class LiveViewServiceImpl : CoroutineVerticle(), LiveViewService {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    internal lateinit var meterSystem: MeterSystem
    internal lateinit var meterProcessService: MeterProcessService
    private lateinit var metricsQuery: MetricsQueryService
    private lateinit var traceQuery: TraceQueryService
    private lateinit var aggregationQuery: AggregationQueryService

    //todo: use ExpiringSharedData
    private val subscriptionCache = MetricTypeSubscriptionCache()
    val meterView = LiveMeterView(subscriptionCache)
    val traceView = LiveTraceView(subscriptionCache)
    val logView = LiveLogView(subscriptionCache)

    override suspend fun start() {
        log.debug("Starting LiveViewServiceImpl")
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
        FeedbackProcessor.module!!.find(CoreModule.NAME).provider().apply {
            aggregationQuery = getService(AggregationQueryService::class.java) as AggregationQueryService
        }

        //load preset view rules
        val viewRules = SourceStorage.getViewRules()
        val livePresets = ClusterConnection.config.getJsonObject("live-presets") ?: JsonObject()
        livePresets.map.keys.forEach {
            val presetName = it
            val preset = livePresets.getJsonObject(presetName)
            if (preset.getString("enabled").toBooleanStrict()) {
                val viewRulesArr = preset.getJsonArray("view-rules", JsonArray())
                Vertx.currentContext().putLocal("developer", DeveloperAuth("system"))
                viewRulesArr.forEach {
                    val viewRule = ViewRule(JsonObject.mapFrom(it))
                    saveRuleIfAbsent(viewRule).await()
                }
                Vertx.currentContext().removeLocal("developer")
                if (viewRulesArr.size() > 0) {
                    log.info { "Loaded ${viewRulesArr.size()} live view rules from preset '$presetName'" }
                }
            }
        }
        //load view rules from database
        viewRules.forEach {
            saveRuleIfAbsent(it).await()
        }
        if (viewRules.isNotEmpty()) {
            log.info { "Loaded ${viewRules.size} live view rules from database" }
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

    override fun getRules(): Future<List<ViewRule>> {
        val rules = mutableListOf<ViewRule>()
        meterProcessService.converts().forEach { convert ->
            if (convert !is LiveMetricConvert) return@forEach
            val rule = convert.config.getLiveMetricsRules().first().rule
            rules.add(rule.copy(name = "spp_" + rule.name))
        }
        return Future.succeededFuture(rules)
    }

    override fun getRule(ruleName: String): Future<ViewRule?> {
        return getRules().map { it.firstOrNull { it.name == ruleName } }
    }

    override fun saveRule(rule: ViewRule): Future<ViewRule> {
        //setup spp rule
        var saveRule = rule
        if (saveRule.name.startsWith("spp_")) {
            saveRule = saveRule.copy(name = saveRule.name.substring(4))
        }
        val meterConfig = LiveMeterConfig()
        meterConfig.metricPrefix = "spp"
        meterConfig.metricsRules = listOf(LiveMeterConfig.Rule(saveRule))

        //check for existing rule
        val exitingRule = meterProcessService.converts().any { ruleset ->
            if (ruleset !is LiveMetricConvert) return@any false
            ruleset.config.getLiveMetricsRules().any { it.rule.name == saveRule.name }
        }
        if (exitingRule) {
            return Future.failedFuture(RuleAlreadyExistsException("Rule with name ${saveRule.name} already exists"))
        }
        log.info { "Saving live view rule: $saveRule" }

        //create spp ruleset
        val promise = Promise.promise<ViewRule>()
        launch(vertx.dispatcher()) {
            try {
                val passRule = if (saveRule.partitions.isEmpty()) meterConfig else NOP_RULE
                meterProcessService.converts().add(LiveMetricConvert(meterConfig, meterSystem, passRule))
                SourceStorage.addViewRule(saveRule)
                promise.complete(saveRule)
            } catch (e: Exception) {
                promise.fail(e)
            }
        }
        return promise.future()
    }

    override fun deleteRule(ruleName: String): Future<ViewRule?> {
        var deleteRuleName = ruleName
        if (deleteRuleName.startsWith("spp_")) {
            deleteRuleName = deleteRuleName.substring(4)
        }

        var removedRule: ViewRule? = null
        (meterProcessService.converts() as MutableList<MetricConvert>).removeIf { convert ->
            if (convert !is LiveMetricConvert) return@removeIf false
            val rule = convert.config.getLiveMetricsRules().firstOrNull { it.rule.name == deleteRuleName }
            if (rule != null) {
                removedRule = rule.rule
                return@removeIf true
            } else {
                return@removeIf false
            }
        }

        val promise = Promise.promise<ViewRule?>()
        launch(vertx.dispatcher()) {
            try {
                SourceStorage.removeViewRule(deleteRuleName)
                promise.complete(removedRule)
            } catch (e: Exception) {
                promise.fail(e)
            }
        }
        return promise.future()
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
                    firstEvent.getString("timeBucket"),
                    sub.viewConfig,
                    events.toString()
                )
            } else {
                LiveViewEvent(
                    sub.subscriptionId!!,
                    sub.entityIds.first(),
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
        var unsubbedSubscriber: ViewSubscriber? = null
        subscriptionCache.flatMap { it.value.values }.forEach { subList ->
            val subscription = subList.firstOrNull { it.subscription.subscriptionId == id }
            if (subscription != null) {
                (subList as MutableSet).remove(subscription)
                unsubbedSubscriber = subscription
            }
        }

        if (unsubbedSubscriber != null) {
            val removedView = LiveView(
                unsubbedSubscriber!!.subscription.entityIds,
                unsubbedSubscriber!!.subscription.viewConfig,
                unsubbedSubscriber!!.subscription.location,
                unsubbedSubscriber!!.subscription.subscriptionId,
            )
            log.debug { "Removed live view: {}".args(removedView) }

            unsubbedSubscriber!!.consumer.unregister {
                if (it.succeeded()) {
                    promise.complete(removedView)
                } else {
                    log.error(it.cause()) { "Failed to unregister live view consumer" }
                    promise.fail(it.cause())
                }
            }
        } else {
            log.error("Non-existent live view: {}", id)
            promise.fail("Non-existent live view: $id")
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
            log.error("Non-existent live view: {}", id)
            promise.fail("Non-existent live view: $id")
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
                    subbedUser!!.subscription.entityIds,
                    subbedUser!!.subscription.viewConfig,
                    subbedUser!!.subscription.location,
                    subbedUser!!.subscription.subscriptionId,
                )
            )
        } else {
            log.error("Non-existent live view: {}", id)
            promise.fail("Non-existent live view: $id")
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

    override fun sortMetrics(
        name: String,
        parentService: String?,
        normal: Boolean?,
        scope: Scope?,
        topN: Int,
        order: Order,
        step: MetricStep,
        start: Instant,
        stop: Instant?
    ): Future<List<SelectedRecord>> {
        log.debug { "Sorting metrics" }
        val promise = Promise.promise<List<SelectedRecord>>()
        launch(vertx.dispatcher()) {
            try {
                aggregationQuery.sortMetrics(TopNCondition().apply {
                    this.name = name
                    this.parentService = parentService
                    this.isNormal = normal ?: false
                    this.scope = SkyWalkingScope.valueOf(scope?.name ?: "ALL")
                    this.topN = topN
                    this.order = SkyWalkingOrder.valueOf(order.name)
                }, Duration().apply {
                    this.start = step.formatter.format(start)
                    this.end = step.formatter.format(stop ?: Instant.now())
                    this.step = Step.valueOf(step.name)
                }).map { JsonObject.mapFrom(it) }.map { SelectedRecord(it) }.let {
                    promise.complete(it)
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to sort metrics" }
                promise.fail(e)
            }
        }
        return promise.future()
    }

    override fun getHistoricalMetrics(
        entityIds: List<String>,
        metricIds: List<String>,
        step: MetricStep,
        start: Instant,
        stop: Instant?,
        labels: List<String>
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
                    if (!ValueColumnMetadata.INSTANCE.readValueColumnDefinition(metricType.metricId).isPresent) {
                        historicalView.data.add(
                            JsonObject().put("metricId", metricId)
                                .put("error", "Metric type not found")
                        )

                        return@forEach
                    }

                    val values = getHistoricalMetrics(metricType, entityId, duration, labels)
                    values.forEach { metric ->
                        historicalView.data.add((metric as JsonObject).put("metricId", metricId))
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

    private fun getHistoricalMetrics(
        metricType: MetricType,
        entityId: String,
        duration: Duration,
        labels: List<String>
    ): JsonArray {
        val service = try {
            Service.fromId(entityId)
        } catch (ignored: Exception) {
            null
        }

        return if (service != null) {
            val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
            val managementService = LiveManagementService.createProxy(vertx, devAuth.accessToken)
            val searchServices = runBlocking(vertx.dispatcher()) {
                managementService.getActiveServices(service.name).await()
            }

            val allMetrics = mutableListOf<JsonArray>()
            searchServices.forEach {
                val array = searchService(metricType, it.id, labels, duration)
                for (i in 0 until array.size()) {
                    array.getJsonObject(i).put("service", it.toJson())
                }
                allMetrics.add(array)
            }

            val mergeArray = JsonArray()
            repeat(allMetrics.firstOrNull()?.size() ?: 0) {
                mergeArray.add(JsonObject())
            }
            allMetrics.forEach {
                it.forEachIndexed { i, metrics ->
                    val a = mergeArray.getJsonObject(i)
                    val b = metrics as JsonObject
                    if (a.getValue("value") == null) {
                        a.mergeIn(b)
                    } //todo: more accurate merging (env, metric type, etc)
                }
            }
            mergeArray
        } else {
            searchService(metricType, entityId, labels, duration)
        }
    }

    private fun searchService(
        metricType: MetricType,
        entityId: String,
        labels: List<String>,
        duration: Duration
    ): JsonArray {
        val condition = MetricsCondition()
        condition.name = metricType.metricId
        condition.entity = object : Entity() {
            override fun buildId(): String = entityId
            override fun isValid(): Boolean = true
        }

        return if (labels.isNotEmpty()) {
            JsonArray().apply {
                metricsQuery.readLabeledMetricsValues(condition, labels, duration).forEach {
                    val label = it.label
                    it.values.values.forEach {
                        val valueOb = JsonObject().put("label", label)
                        if (!it.isEmptyValue) {
                            valueOb.put("value", it.value)
                        }
                        add(valueOb)
                    }
                }
            }
        } else {
            JsonArray().apply {
                metricsQuery.readMetricsValues(condition, duration).values.values.forEach {
                    val valueOb = JsonObject()
                    if (!it.isEmptyValue) {
                        valueOb.put("value", it.value)
                    }
                    add(valueOb)
                }
            }
        }
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
