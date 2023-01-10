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
package spp.platform.core.service

import io.vertx.core.*
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
import io.vertx.serviceproxy.ServiceInterceptor
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.slf4j.LoggerFactory
import spp.platform.common.DeveloperAuth
import spp.protocol.platform.auth.RolePermission
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.service.LiveManagementService
import spp.protocol.service.SourceServices.LIVE_MANAGEMENT
import spp.protocol.service.error.PermissionAccessDenied
import kotlin.system.exitProcess

class ServiceProvider(
    private val jwtAuth: JWTAuth?,
    private val moduleManager: ModuleManager
) : CoroutineVerticle() {

    companion object {
        private val log = LoggerFactory.getLogger(ServiceProvider::class.java)
    }

    private lateinit var discovery: ServiceDiscovery
    private lateinit var managementServiceRecord: Record
    private lateinit var managementService: LiveManagementService

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

            managementService = LiveManagementServiceImpl(vertx, jwtAuth, moduleManager)
            managementServiceRecord = publishService(
                LIVE_MANAGEMENT,
                LiveManagementService::class.java,
                managementService
            )
        } catch (throwable: Throwable) {
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
            .addInterceptor(permissionCheckInterceptor())
            .register(clazz, service)
        val record = EventBusService.createRecord(
            address, address, clazz,
            JsonObject().put("INSTANCE_ID", config.getString("SPP_INSTANCE_ID"))
        )
        discovery.publish(record).await()
        return record
    }

    private fun permissionCheckInterceptor(): ServiceInterceptor {
        return ServiceInterceptor { _, _, msg ->
            val promise = Promise.promise<Message<JsonObject>>()
            managementService.getSelf().onSuccess { selfInfo ->
                validateRolePermission(selfInfo, msg) {
                    if (it.succeeded()) {
                        promise.complete(msg)
                    } else {
                        promise.fail(it.cause())
                    }
                }
            }.onFailure {
                promise.fail(it)
            }
            promise.future()
        }
    }

    private fun validateRolePermission(
        selfInfo: SelfInfo,
        msg: Message<JsonObject>,
        handler: Handler<AsyncResult<Message<JsonObject>>>
    ) {
        fun failsPermissionCheck(
            permission: RolePermission
        ): Boolean {
            if (!selfInfo.permissions.contains(permission)) {
                log.warn("User ${selfInfo.developer.id} missing permission: $permission")
                handler.handle(Future.failedFuture(PermissionAccessDenied.asEventBusException(permission)))
                return true
            }
            return false
        }

        val action = msg.headers().get("action")
        if (action == "getAccessPermission" || action == "getRoleAccessPermissions" || action == "getDeveloperAccessPermissions") {
            if (failsPermissionCheck(RolePermission.GET_ACCESS_PERMISSIONS)) return
        } else if (action == "getDataRedaction" || action == "getRoleDataRedactions" || action == "getDeveloperDataRedactions") {
            if (failsPermissionCheck(RolePermission.GET_DATA_REDACTIONS)) return
        } else if (action == "addDataRedaction" || action == "removeDataRedaction" || action == "addRoleDataRedaction" || action == "removeRoleDataRedaction") {
            if (failsPermissionCheck(RolePermission.UPDATE_DATA_REDACTION)) return
        } else if (action == "addRoleAccessPermission" || action == "removeRoleAccessPermission") {
            if (failsPermissionCheck(RolePermission.ADD_ACCESS_PERMISSION)) return
        } else if (action == "refreshClientAccess") {
            if (failsPermissionCheck(RolePermission.UPDATE_CLIENT_ACCESS)) return
        } else if (RolePermission.fromString(action) != null) {
            val necessaryPermission = RolePermission.fromString(action)!!
            if (failsPermissionCheck(necessaryPermission)) return
        }
        handler.handle(Future.succeededFuture(msg))
    }

    override suspend fun stop() {
        discovery.unpublish(managementServiceRecord.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live management service unpublished")
            } else {
                log.error("Failed to unpublish live management service", it.cause())
            }
        }.await()
        discovery.close()
    }
}
