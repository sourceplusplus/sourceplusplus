/*
 * Source++, the continuous feedback platform for developers.
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
package integration

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import io.vertx.spi.cluster.redis.RedisClusterManager
import io.vertx.spi.cluster.redis.config.RedisConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
open class PlatformIntegrationTest {

    companion object {
        private val log = LoggerFactory.getLogger(PlatformIntegrationTest::class.java)

        const val SYSTEM_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJzeXN0ZW0iLCJjcmVhdGVkX2F0IjoxNjU3MDM5NzAzOTE1L" +
                    "CJleHBpcmVzX2F0IjoxNjg4NTc1NzAzOTE1LCJpYXQiOjE2NTcwMzk3MDN9.hKxtqnajBWbWxL2nYoVyp9HeyDfIi5XjRRkJtI" +
                    "wms6JOfWCWO9TG2ghW7nhv2N7c0G6JMGrelWCfXesZ33z0epz4XcJ6s05gV8EXkQjQPKzPQ770w2QHH4IenUKWBn44r0LxteAd" +
                    "KGVmaheqJ9Gr4hDN2PzzQS5i_WM34N-ucbfUwQ79rUyQaEcDvgywnL8kUSNDlhnYb2gyVMYC5_QxNDusxCUJq6Kas1qHzmg02t" +
                    "7ToWNzHCGxa7LWJkgx27BMhFSubq8fMUtzP6YWQs4gXLfvVzc3i5VxevJf7dFWw1VsfpW31qfdkmZp89BueaaZpJh236HMnhxM" +
                    "CwsbCWKaIZgQqGzFL9sZzH-Aav8AM9CRJYpnN0eTl6Bsqbhh2AsS-EycV_O-9NDA4Ac8ImeaGw4kqMwZSVeSMRhSgaWiwmXASL" +
                    "gNM6LgKVKSAgPXIKrSEPmo9_mFPMwY8uxnu-J0uGmTa1hQEtmblAXBuZbyP1CtOk_Hmydif5K-jFnOne5fyj-Ju5q7uVjz60FH" +
                    "u5TQSJZX2U6YmpHlRVpDjs8g8EThjta7DSkmCNEPfn0YU_Cx9cSphEQkuZdyu_C8rPGzMDqyziOu1yWZYfFxK5SVMsGjfIOeB4" +
                    "qEDARssQ8-oREcp52Q4MmmT8d0oN4I4Fm_aa9X-R6dDkeWrFGrzcVEL1o"

        const val TEST_JWT_TOKEN =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJkZXZlbG9wZXJfaWQiOiJ0ZXN0IiwiY3JlYXRlZF9hdCI6MTY1NzAzOTc1ODM5MiwiZ" +
                    "XhwaXJlc19hdCI6MTY4ODU3NTc1ODM5MiwiaWF0IjoxNjU3MDM5NzU4fQ.h-fkoLkMqphwMVeXIz9weXDYe3NgLrIIWYZcEGLD" +
                    "0DOvjlRGhXVPte2E6o_IJCUWys4g3V4TbT9LCdVTAt9ay8850ivlfAEnL-rOt3gaKxZF0AOxfdBQCb6PODOkHULhEMuM3Cy9oL" +
                    "g7BRu8dZFoMRdbwWslihsLk9bXDyPCcuNKjdcBr8n_fFfbGUrO0HdWIhGEj7qnujzFbPk2m4CbnMTcgHXxTYma8uaxpa03EU2p" +
                    "fZbQiauIHATsmvUvtkoGCnR5W0Vpz7M-1qo1JNphEMfxrsa_-8mbCkjG7rEXpWFvWfW6FwObKT3xs5D8jRpAmX0kCyfe9lPZ2e" +
                    "J-Rdhy95f6LpjJoXkaPLGVQpU_lsPUk8yb410s2dTkY47FzZojTtY6gfl3IHDXnkG8FcaV6qqbTpTyz16rN9CxAynrg-7QsYdr" +
                    "cZpT8A-6cjHuCJabgFxqKNm7Jfknu_f34q-_rZJpVyeC_2AYDETEB3AwCiWoTAurTAhPF4ZoMbfkie75TTF3Hjhbimw7PnzHg2" +
                    "WbvSQpHyTR96rII4rjpqtFwC49CmPOCJPxLpEpa5rMW3gbvKa-M1IJRSnKN0rsPF1Yr80LIl4x1w6JZp47_MoYPe0rWNPojnBY" +
                    "KP-1-SRBc1skSsijxtOWiYoz2UbA9xIDWUrWh6g-wUFa-xE4eAjFKgE"

        private var vertx: Vertx? = null
        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"

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
    }

    val vertx: Vertx = vertx()

    val managementService: LiveManagementService
        get() {
            return LiveManagementService.createProxy(vertx, SYSTEM_JWT_TOKEN)
        }
    val instrumentService: LiveInstrumentService
        get() {
            return LiveInstrumentService.createProxy(vertx, SYSTEM_JWT_TOKEN)
        }
    val viewService: LiveViewService
        get() {
            return LiveViewService.createProxy(vertx, SYSTEM_JWT_TOKEN)
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

    fun <T> MessageConsumer<T>.completionHandler() : Future<Void> {
        val promise = Promise.promise<Void>()
        completionHandler { promise.handle(it) }
        return promise.future()
    }
}
