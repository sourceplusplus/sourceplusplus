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
package integration

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.RequestOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import io.vertx.spi.cluster.redis.RedisClusterManager
import io.vertx.spi.cluster.redis.config.LockConfig
import io.vertx.spi.cluster.redis.config.RedisConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveInstrument
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import spp.protocol.view.LiveView
import spp.protocol.view.rule.ViewRule
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@ExtendWith(VertxExtension::class)
open class PlatformIntegrationTest {

    val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

    lateinit var testName: String
    val testNameAsInstrumentId: String
        get() {
            return "spp_" + testName.replace("-", "_").replace(" ", "_")
                .lowercase().substringBefore("(")
        }
    val testNameAsUniqueInstrumentId: String
        get() {
            return testNameAsInstrumentId + "_" + UUID.randomUUID().toString().replace("-", "")
        }

    @BeforeEach
    open fun setUp(testInfo: TestInfo) {
        testName = testInfo.displayName
    }

    companion object {
        lateinit var vertx: Vertx
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        const val platformPort = 12800
        val systemAccessToken: String? by lazy { fetchAccessToken() }

        @BeforeAll
        @JvmStatic
        @Synchronized
        fun setup() {
            if (::vertx.isInitialized) return
            val clusterStorageAddress = "redis://localhost:6379"
            val clusterManager = RedisClusterManager(
                RedisConfig()
                    .setKeyNamespace("cluster")
                    .addEndpoint(clusterStorageAddress)
                    .addLock(LockConfig(Pattern.compile("expiring_shared_data:.*")).setLeaseTime(5000))
            )
            runBlocking {
                vertx = Vertx.clusteredVertx(VertxOptions().setClusterManager(clusterManager)).await()
            }
        }

        private fun fetchAccessToken() = runBlocking {
            val tokenUri = "/api/new-token?authorization_code=change-me"
            val req = vertx.createHttpClient(HttpClientOptions())
                .request(
                    RequestOptions()
                        .setHost(platformHost)
                        .setPort(platformPort)
                        .setURI(tokenUri)
                ).await()
            req.end().await()
            val resp = req.response().await()
            if (resp.statusCode() == 200) {
                resp.body().await().toString()
            } else {
                null
            }
        }
    }

    val managementService: LiveManagementService
        get() {
            return LiveManagementService.createProxy(vertx, systemAccessToken)
        }
    val instrumentService: LiveInstrumentService
        get() {
            return LoggedLiveInstrumentService(LiveInstrumentService.createProxy(vertx, systemAccessToken))
        }
    val viewService: LiveViewService
        get() {
            return LoggedLiveViewService(LiveViewService.createProxy(vertx, systemAccessToken))
        }

    fun errorOnTimeout(testContext: VertxTestContext, waitTime: Long = 60) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    fun successOnTimeout(testContext: VertxTestContext, waitTime: Long = 30) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            testContext.completeNow()
        }
    }

    fun <T> MessageConsumer<T>.completionHandler(): Future<Void> {
        val promise = Promise.promise<Void>()
        completionHandler { promise.handle(it) }
        return promise.future()
    }

    class LoggedLiveInstrumentService(private val delegate: LiveInstrumentService) : LiveInstrumentService by delegate {

        private val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

        override fun addLiveInstrument(instrument: LiveInstrument): Future<LiveInstrument> {
            log.info("Adding live instrument {}", instrument)
            val value = delegate.addLiveInstrument(instrument)
            return value.map {
                log.info("Added live instrument {}: {}", it.id, it)
                it
            }
        }

        override fun addLiveInstruments(instruments: List<LiveInstrument>): Future<List<LiveInstrument>> {
            log.info("Adding live instruments {}", instruments)
            val value = delegate.addLiveInstruments(instruments)
            return value.map {
                log.info("Added live instruments {}", it)
                it
            }
        }

        override fun removeLiveInstrument(id: String): Future<LiveInstrument?> {
            log.info("Removing live instrument {}", id)
            val value = delegate.removeLiveInstrument(id)
            return value.map {
                log.info("Removed live instrument {}: {}", id, it)
                it
            }
        }
    }

    class LoggedLiveViewService(private val delegate: LiveViewService) : LiveViewService by delegate {

        private val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

        override fun removeLiveView(id: String): Future<LiveView> {
            log.info("Removing live view {}", id)
            val value = delegate.removeLiveView(id)
            return value.map {
                log.info("Removed live view {}: {}", id, it)
                it
            }
        }

        override fun deleteRule(ruleName: String): Future<ViewRule?> {
            log.info("Deleting rule {}", ruleName)
            val value = delegate.deleteRule(ruleName)
            return value.map {
                log.info("Deleted rule {}: {}", ruleName, it)
                it
            }
        }
    }
}
