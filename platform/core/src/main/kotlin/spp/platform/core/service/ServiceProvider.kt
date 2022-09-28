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
package spp.platform.core.service

import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery
import io.vertx.servicediscovery.ServiceDiscoveryOptions
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import org.slf4j.LoggerFactory
import spp.platform.common.DeveloperAuth
import spp.protocol.SourceServices.LIVE_MANAGEMENT_SERVICE
import spp.protocol.service.LiveManagementService
import kotlin.system.exitProcess

class ServiceProvider(private val jwtAuth: JWTAuth?) : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(ServiceProvider::class.java)
    }

    private var discovery: ServiceDiscovery? = null
    private var liveService: Record? = null
    private var liveManagementService: Record? = null

    override suspend fun start() {
        try {
            discovery = if (config.getJsonObject("storage").getString("selector") == "redis") {
                val sdHost = config.getJsonObject("storage").getJsonObject("redis").getString("host")
                val sdPort = config.getJsonObject("storage").getJsonObject("redis").getString("port")
                ServiceDiscovery.create(
                    vertx, ServiceDiscoveryOptions().setBackendConfiguration(
                        JsonObject()
                            .put("connectionString", "redis://$sdHost:$sdPort")
                            .put("key", "records")
                    )
                )
            } else {
                ServiceDiscovery.create(vertx, ServiceDiscoveryOptions())
            }

            liveManagementService = publishService(
                LIVE_MANAGEMENT_SERVICE,
                LiveManagementService::class.java,
                LiveManagementServiceImpl(vertx)
            )
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
            log.error("Failed to start SkyWalking provider", throwable)
            exitProcess(-1)
        }
    }

    private suspend fun <T> publishService(address: String, clazz: Class<T>, service: T): Record {
        ServiceBinder(vertx).setIncludeDebugInfo(true).setAddress(address)
            .addInterceptor { msg ->
                val promise = Promise.promise<Message<JsonObject>>()
                if (jwtAuth != null) {
                    jwtAuth.authenticate(JsonObject().put("token", msg.headers().get("auth-token"))).onComplete {
                        if (it.succeeded()) {
                            Vertx.currentContext().putLocal("user", it.result())
                            val selfId = it.result().principal().getString("developer_id")
                            val accessToken = it.result().principal().getString("access_token")
                            Vertx.currentContext().putLocal("developer", DeveloperAuth.from(selfId, accessToken))
                            promise.complete(msg)
                        } else {
                            promise.fail(it.cause())
                        }
                    }
                } else {
                    Vertx.currentContext().putLocal("developer", DeveloperAuth.from("system", null))
                    promise.complete(msg)
                }
                return@addInterceptor promise.future()
            }
            .register(clazz, service)
        val record = EventBusService.createRecord(
            address, address, clazz,
            JsonObject().put("INSTANCE_ID", config.getString("SPP_INSTANCE_ID"))
        )
        discovery!!.publish(record).await()
        return record
    }

    override suspend fun stop() {
        discovery!!.unpublish(liveService!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live service unpublished")
            } else {
                log.error("Failed to unpublish live service", it.cause())
            }
        }.await()
        discovery!!.unpublish(liveManagementService!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live management service unpublished")
            } else {
                log.error("Failed to unpublish live management service", it.cause())
            }
        }.await()
        discovery!!.close()
    }
}
