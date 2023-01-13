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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.instrument.LiveInstrument
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@ExtendWith(VertxExtension::class)
open class PlatformIntegrationTest {

    var testName: String? = null
    val testNameAsInstrumentId: String
        get() {
            return testName!!.replace(" ", "-").lowercase().substringBefore("(")
        }

    @BeforeEach
    open fun setUp(testInfo: TestInfo) {
        testName = testInfo.displayName
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlatformIntegrationTest::class.java)

        private var vertx: Vertx? = null
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        const val platformPort = 12800
        val systemAuthToken: String? by lazy { fetchAuthToken() }

        fun vertx(): Vertx {
            return vertx!!
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            val clusterStorageAddress = "redis://localhost:6379"
            val clusterManager = RedisClusterManager(
                RedisConfig()
                    .setKeyNamespace("cluster")
                    .addEndpoint(clusterStorageAddress)
                    .addLock(LockConfig(Pattern.compile("expiring_shared_data:.*")).setLeaseTime(5000))
            )
            runBlocking {
                log.info("Starting vertx")
                vertx = Vertx.clusteredVertx(VertxOptions().setClusterManager(clusterManager)).await()
                log.info("Started vertx")
            }
        }

        @AfterAll
        @JvmStatic
        fun destroy() {
            runBlocking {
                log.info("Closing vertx")
                vertx!!.close().await()
                vertx = null
                log.info("Closed vertx")
            }
        }

        private fun fetchAuthToken() = runBlocking {
            val tokenUri = "/api/new-token?access_token=change-me"
            val req = vertx!!.createHttpClient(HttpClientOptions())
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

    val vertx: Vertx = vertx()

    val managementService: LiveManagementService
        get() {
            return LiveManagementService.createProxy(vertx, systemAuthToken)
        }
    val instrumentService: LiveInstrumentService
        get() {
            return LoggedLiveInstrumentService(LiveInstrumentService.createProxy(vertx, systemAuthToken))
        }
    val viewService: LiveViewService
        get() {
            return LiveViewService.createProxy(vertx, systemAuthToken)
        }

    fun errorOnTimeout(testContext: VertxTestContext, waitTime: Long = 15) {
        if (testContext.awaitCompletion(waitTime, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    fun successOnTimeout(testContext: VertxTestContext, waitTime: Long = 15) {
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
        override fun removeLiveInstrument(id: String): Future<LiveInstrument?> {
            log.debug("Removing live instrument $id", Throwable())
            return delegate.removeLiveInstrument(id)
        }
    }
}
