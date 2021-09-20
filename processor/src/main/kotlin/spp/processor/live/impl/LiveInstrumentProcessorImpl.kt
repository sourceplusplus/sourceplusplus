package spp.processor.live.impl

import io.vertx.kotlin.coroutines.CoroutineVerticle
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
}
