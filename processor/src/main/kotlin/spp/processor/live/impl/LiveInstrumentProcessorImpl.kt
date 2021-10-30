package spp.processor.live.impl

import com.sourceplusplus.protocol.instrument.meter.LiveMeter
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.apache.skywalking.oap.meter.analyzer.MetricConvert
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.config.MeterConfig
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.MeterProcessService
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.meter.MeterSystem
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.core.storage.query.ILogQueryDAO
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO
import org.slf4j.LoggerFactory
import spp.processor.SourceProcessor
import spp.processor.live.LiveInstrumentProcessor

class LiveInstrumentProcessorImpl : CoroutineVerticle(), LiveInstrumentProcessor {

    companion object {
        private val log = LoggerFactory.getLogger(LiveInstrumentProcessorImpl::class.java)
    }

    private lateinit var elasticSearch: EsDAO

    override suspend fun start() {
        log.info("Starting LiveInstrumentProcessorImpl")
        elasticSearch = SourceProcessor.module!!.find(StorageModule.NAME).provider()
            .getService(ILogQueryDAO::class.java) as EsDAO
    }

    override suspend fun stop() {
        log.info("Stopping LiveInstrumentProcessorImpl")
    }

    override fun setupLiveMeter(liveMeter: LiveMeter, handler: Handler<AsyncResult<JsonObject>>) {
        val meterSystem = SourceProcessor.module!!.find(CoreModule.NAME).provider().getService(MeterSystem::class.java)
        val meterConfig = MeterConfig()
        meterConfig.metricPrefix = "spp"
        meterConfig.metricsRules = mutableListOf(
            MeterConfig.Rule().apply {
                val idVariable = "meter_" + liveMeter.id!!.replace("-", "_")
                name = idVariable
                exp = "($idVariable.sum(['service', 'instance'])).instance(['service'], ['instance'])"
            }
        )
        val service = SourceProcessor.module!!.find(AnalyzerModule.NAME).provider()
            .getService(IMeterProcessService::class.java) as MeterProcessService
        service.converts().add(MetricConvert(meterConfig, meterSystem))
        handler.handle(Future.succeededFuture(JsonObject()))
    }
}
