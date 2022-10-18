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
package spp.platform.core.service

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.common.DeveloperAuth
import spp.platform.common.service.SourceBridgeService
import spp.platform.storage.SourceStorage
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.auth.ClientAccess
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RolePermission
import spp.protocol.platform.developer.Developer
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.platform.general.Service
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import spp.protocol.service.SourceServices
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class LiveManagementServiceImpl(private val vertx: Vertx) : LiveManagementService {

    companion object {
        private val log = KotlinLogging.logger {}
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
            .withZone(ZoneId.systemDefault())
    }

    override fun getClients(): Future<JsonObject> {
        log.trace { "Getting clients" }
        val promise = Promise.promise<JsonObject>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(
                JsonObject().apply {
                    val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
                    val bridgeService = SourceBridgeService.service(vertx, devAuth?.accessToken).await()
                    if (bridgeService != null) {
                        log.trace { "Getting clients from bridge service" }
                        put("markers", bridgeService.getActiveMarkers().await())
                        put("probes", bridgeService.getActiveProbes().await())
                        log.trace { "Added markers/probes clients to response" }
                    }
                }
            )
        }
        return promise.future()
    }

    override fun getStats(): Future<JsonObject> {
        log.trace { "Getting platform stats" }
        val promise = Promise.promise<JsonObject>()
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")

        val subStats = Promise.promise<JsonObject>()
        LiveViewService.createProxy(vertx, devAuth.accessToken).getLiveViewStats().onComplete {
            if (it.succeeded()) {
                subStats.complete(it.result())
            } else {
                subStats.fail(it.cause())
            }
        }

        GlobalScope.launch(vertx.dispatcher()) {
            val platformStats = getPlatformStats()
            subStats.future().onSuccess {
                promise.complete(
                    JsonObject()
                        .put("platform", platformStats)
                        .put("subscriptions", it)
                )
            }.onFailure {
                promise.fail(it)
            }
        }
        return promise.future()
    }

    private suspend fun getPlatformStats(): JsonObject {
        log.trace { "Getting platform stats" }
        return JsonObject()
            .apply {
                val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
                val bridgeService = SourceBridgeService.service(vertx, devAuth?.accessToken).await()
                if (bridgeService != null) {
                    log.trace { "Getting platform stats from bridge service" }
                    put("connected-markers", bridgeService.getConnectedMarkers().await())
                    put("connected-probes", bridgeService.getConnectedProbes().await())
                    log.trace { "Added markers/probes counts to response" }
                }
            }
            .put(
                "services", //todo: get services from service registry
                JsonObject()
                    .put(
                        "core",
                        JsonObject()
                            .put(
                                SourceServices.LIVE_MANAGEMENT_SERVICE,
                                SourceStorage.counter(SourceServices.LIVE_MANAGEMENT_SERVICE).get().await()
                            )
                            .put(
                                SourceServices.LIVE_INSTRUMENT,
                                SourceStorage.counter(SourceServices.LIVE_INSTRUMENT).get().await()
                            )
                            .put(
                                SourceServices.LIVE_VIEW,
                                SourceStorage.counter(SourceServices.LIVE_VIEW).get().await()
                            )
                    )
                    .put(
                        "probe",
                        JsonObject()
                            .put(
                                ProbeAddress.LIVE_INSTRUMENT_REMOTE,
                                SourceStorage.counter(ProbeAddress.LIVE_INSTRUMENT_REMOTE).get().await()
                            )
                    )
            )
    }

    override fun getSelf(): Future<SelfInfo> {
        log.trace { "Getting self info" }
        val promise = Promise.promise<SelfInfo>()
        val selfId = Vertx.currentContext().getLocal<DeveloperAuth>("developer").selfId

        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(
                SelfInfo(
                    developer = Developer(selfId),
                    roles = SourceStorage.getDeveloperRoles(selfId),
                    permissions = SourceStorage.getDeveloperPermissions(selfId).toList(),
                    access = SourceStorage.getDeveloperAccessPermissions(selfId)
                )
            )
        }
        return promise.future()
    }

    override fun getServices(): Future<List<Service>> {
        log.trace { "Getting services" }
        val promise = Promise.promise<List<Service>>()
        val forward = JsonObject()
        forward.put("developer_id", Vertx.currentContext().getLocal<DeveloperAuth>("developer").selfId)
        forward.put("method", HttpMethod.POST.name())
        forward.put(
            "body", JsonObject()
                .put(
                    "query", "query (\$durationStart: String!, \$durationEnd: String!, \$durationStep: Step!) {\n" +
                            "  getAllServices(duration: {start: \$durationStart, end: \$durationEnd, step: \$durationStep}) {\n" +
                            "    key: id\n" +
                            "    label: name\n" +
                            "  }\n" +
                            "}"
                )
                .put(
                    "variables", JsonObject()
                        .put("durationStart", formatter.format(Instant.now().minus(365, ChronoUnit.DAYS)))
                        .put("durationEnd", formatter.format(Instant.now()))
                        .put("durationStep", "MINUTE")
                )
        )

        vertx.eventBus().request<JsonObject>("skywalking-forwarder", forward) {
            if (it.succeeded()) {
                val response = it.result().body()
                val body = JsonObject(response.getString("body"))
                val data = body.getJsonObject("data")
                val services = data.getJsonArray("getAllServices")
                val result = mutableListOf<Service>()
                for (i in 0 until services.size()) {
                    val service = services.getJsonObject(i)
                    result.add(
                        Service(
                            id = service.getString("key"),
                            name = service.getString("label")
                        )
                    )
                }
                promise.complete(result)
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    override fun getActiveProbes(): Future<List<InstanceConnection>> {
        log.trace { "Getting active probes" }
        val promise = Promise.promise<List<InstanceConnection>>()
        GlobalScope.launch(vertx.dispatcher()) {
            val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
            val bridgeService = SourceBridgeService.service(vertx, devAuth?.accessToken).await()
            if (bridgeService != null) {
                promise.complete(
                    bridgeService.getActiveProbes().await().list.map {
                        InstanceConnection(JsonObject.mapFrom(it))
                    }
                )
            } else {
                promise.fail("Bridge service is not available")
            }
        }
        return promise.future()
    }

    override fun getRolePermissions(role: String): Future<List<RolePermission>> {
        log.trace { "Getting role permissions" }
        val promise = Promise.promise<List<RolePermission>>()
        GlobalScope.launch(vertx.dispatcher()) {
            try {
                val permissions = SourceStorage.getRolePermissions(DeveloperRole.fromString(role))
                promise.complete(permissions.toList())
            } catch (e: Exception) {
                log.error("Failed to get role permissions", e)
                promise.fail(e)
            }
        }
        return promise.future()
    }

    override fun getClientAccessors(): Future<List<ClientAccess>> {
        log.trace { "Getting client accessors" }
        val promise = Promise.promise<List<ClientAccess>>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getClientAccessors())
        }
        return promise.future()
    }

    override fun getClientAccess(id: String): Future<ClientAccess?> {
        log.trace { "Getting client access" }
        val promise = Promise.promise<ClientAccess?>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getClientAccess(id))
        }
        return promise.future()
    }

    override fun addClientAccess(): Future<ClientAccess> {
        log.trace { "Adding client access" }
        val promise = Promise.promise<ClientAccess>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.addClientAccess())
        }
        return promise.future()
    }

    override fun removeClientAccess(id: String): Future<Boolean> {
        log.trace { "Removing client access with id: $id" }
        val promise = Promise.promise<Boolean>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.removeClientAccess(id))
        }
        return promise.future()
    }

    override fun refreshClientAccess(id: String): Future<ClientAccess> {
        log.trace { "Refreshing client access with id: $id" }
        val promise = Promise.promise<ClientAccess>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.refreshClientAccess(id))
        }
        return promise.future()
    }

    override fun getActiveProbe(id: String): Future<InstanceConnection?> {
        log.trace { "Getting active probe with id: $id" }
        val promise = Promise.promise<InstanceConnection?>()
        getActiveProbes().onSuccess {
            promise.complete(it.find { it.instanceId == id })
        }.onFailure {
            log.error("Failed to get active probes", it)
            promise.fail(it)
        }
        return promise.future()
    }

    override fun updateActiveProbeMetadata(id: String, metadata: JsonObject): Future<InstanceConnection> {
        log.trace { "Updating active probe metadata with id: $id" }
        val promise = Promise.promise<InstanceConnection>()
        GlobalScope.launch(vertx.dispatcher()) {
            val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
            val bridgeService = SourceBridgeService.service(vertx, devAuth?.accessToken).await()
            if (bridgeService != null) {
                val instance = bridgeService.updateActiveProbeMetadata(id, metadata).await()
                promise.complete(InstanceConnection(JsonObject.mapFrom(instance)))
            } else {
                promise.fail("Bridge service is not available")
            }
        }
        return promise.future()
    }
}
