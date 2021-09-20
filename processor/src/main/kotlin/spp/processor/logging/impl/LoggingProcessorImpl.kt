package spp.processor.logging.impl

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.apache.skywalking.oap.server.core.storage.StorageModule
import org.apache.skywalking.oap.server.core.storage.query.ILogQueryDAO
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder
import org.elasticsearch.search.aggregations.support.ValueType
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.slf4j.LoggerFactory
import spp.processor.SourceProcessor
import spp.processor.logging.LoggingProcessor
import java.util.stream.Collectors

class LoggingProcessorImpl : CoroutineVerticle(), LoggingProcessor {

    companion object {
        private val log = LoggerFactory.getLogger(LoggingProcessorImpl::class.java)
    }

    private lateinit var elasticSearch: EsDAO

    override suspend fun start() {
        log.info("Starting LoggingProcessorImpl")
        elasticSearch = SourceProcessor.module!!.find(StorageModule.NAME).provider()
            .getService(ILogQueryDAO::class.java) as EsDAO
    }

    override fun getPatternOccurredCounts(handler: Handler<AsyncResult<Map<String, Int>>>) {
        //todo: add metric timer
        log.info("Getting pattern occurred counts")
        val size = 1000
        val aggregation = SearchSourceBuilder.searchSource().aggregation(
            TermsAggregationBuilder("content", ValueType.STRING).field("content").size(size)
        ).size(size)
        val logPatternCounts = (elasticSearch.client.search("log", aggregation)
            .aggregations.get("content") as ParsedStringTerms).buckets.stream()
            .collect(Collectors.toMap(Terms.Bucket::getKeyAsString) { it.docCount.toInt() })
        handler.handle(Future.succeededFuture(logPatternCounts))
    }

    override suspend fun stop() {
        log.info("Stopping LoggingProcessorImpl")
    }
}
