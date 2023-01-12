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

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.commons.lang3.RandomStringUtils
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.query.MetadataQueryService
import org.apache.skywalking.oap.server.core.query.enumeration.Step
import org.apache.skywalking.oap.server.core.query.input.Duration
import org.apache.skywalking.oap.server.library.module.ModuleManager
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.platform.common.service.SourceBridgeService
import spp.platform.storage.SourceStorage
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.platform.general.Service
import spp.protocol.platform.general.ServiceEndpoint
import spp.protocol.platform.general.ServiceInstance
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import spp.protocol.service.SourceServices
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class LiveManagementServiceImpl(
    private val vertx: Vertx,
    private val jwt: JWTAuth?,
    moduleManager: ModuleManager,
) : CoroutineVerticle(), LiveManagementService {

    private val log = KotlinLogging.logger {}
    private val metadataQueryService = moduleManager.find(CoreModule.NAME)
        .provider()
        .getService(MetadataQueryService::class.java)

    override fun getVersion(): Future<String> {
        log.trace { "Getting version" }
        val promise = Promise.promise<String>()
        launch(vertx.dispatcher()) {
            promise.complete(ClusterConnection.BUILD.getString("build_version"))
        }
        return promise.future()
    }

    override fun getAccessPermissions(): Future<List<AccessPermission>> {
        log.trace { "Getting access permissions" }
        val promise = Promise.promise<List<AccessPermission>>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getAccessPermissions().toList())
        }
        return promise.future()
    }

    override fun getAccessPermission(id: String): Future<AccessPermission> {
        log.trace { "Getting access permission $id" }
        val promise = Promise.promise<AccessPermission>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasAccessPermission(id)) {
                promise.fail(IllegalStateException("Non-existing access permission: $id"))
            } else {
                promise.complete(SourceStorage.getAccessPermission(id))
            }
        }
        return promise.future()
    }

    override fun addAccessPermission(
        locationPatterns: List<String>,
        type: AccessType
    ): Future<AccessPermission> {
        val id = UUID.randomUUID().toString()
        log.trace { "Adding access permission $id" }
        val promise = Promise.promise<AccessPermission>()
        launch(vertx.dispatcher()) {
            if (SourceStorage.hasAccessPermission(id)) {
                promise.fail(IllegalStateException("Existing access permission: $id"))
            } else {
                val accessPermission = AccessPermission(id, locationPatterns, type)
                SourceStorage.addAccessPermission(id, locationPatterns, type)
                promise.complete(accessPermission)
            }
        }
        return promise.future()
    }

    override fun removeAccessPermission(id: String): Future<Void> {
        log.trace { "Removing access permission $id" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasAccessPermission(id)) {
                promise.fail(IllegalStateException("Non-existing access permission: $id"))
            } else {
                SourceStorage.removeAccessPermission(id)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun getRoleAccessPermissions(role: DeveloperRole): Future<List<AccessPermission>> {
        log.trace { "Getting access permissions for role $role" }
        val promise = Promise.promise<List<AccessPermission>>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else {
                promise.complete(SourceStorage.getRoleAccessPermissions(role).toList())
            }
        }
        return promise.future()
    }

    override fun addRoleAccessPermission(role: DeveloperRole, id: String): Future<Void> {
        log.trace { "Adding access permission $id to role $role" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasAccessPermission(id)) {
                promise.fail(IllegalStateException("Non-existing access permission: $id"))
            } else {
                SourceStorage.addAccessPermissionToRole(id, role)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun removeRoleAccessPermission(role: DeveloperRole, id: String): Future<Void> {
        log.trace { "Removing access permission $id from role $role" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasAccessPermission(id)) {
                promise.fail(IllegalStateException("Non-existing access permission: $id"))
            } else {
                SourceStorage.removeAccessPermissionFromRole(id, role)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun getDataRedactions(): Future<List<DataRedaction>> {
        log.trace { "Getting data redactions" }
        val promise = Promise.promise<List<DataRedaction>>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getDataRedactions().toList())
        }
        return promise.future()
    }

    override fun getDataRedaction(id: String): Future<DataRedaction> {
        log.trace { "Getting data redaction $id" }
        val promise = Promise.promise<DataRedaction>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDataRedaction(id)) {
                promise.fail(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                promise.complete(SourceStorage.getDataRedaction(id))
            }
        }
        return promise.future()
    }

    override fun addDataRedaction(
        id: String,
        type: RedactionType,
        lookup: String,
        replacement: String
    ): Future<DataRedaction> {
        log.trace { "Adding data redaction $id" }
        val promise = Promise.promise<DataRedaction>()
        launch(vertx.dispatcher()) {
            if (SourceStorage.hasDataRedaction(id)) {
                promise.fail(IllegalStateException("Data redaction already exists: $id"))
            } else {
                val redaction = DataRedaction(id, type, lookup, replacement)
                SourceStorage.addDataRedaction(id, type, lookup, replacement)
                promise.complete(redaction)
            }
        }
        return promise.future()
    }

    override fun updateDataRedaction(
        id: String,
        type: RedactionType,
        lookup: String,
        replacement: String
    ): Future<DataRedaction> {
        log.trace { "Updating data redaction $id" }
        val promise = Promise.promise<DataRedaction>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDataRedaction(id)) {
                promise.fail(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                val redaction = DataRedaction(id, type, lookup, replacement)
                SourceStorage.updateDataRedaction(id, type, lookup, replacement)
                promise.complete(redaction)
            }
        }
        return promise.future()
    }

    override fun removeDataRedaction(id: String): Future<Void> {
        log.trace { "Removing data redaction $id" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDataRedaction(id)) {
                promise.fail(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                SourceStorage.removeDataRedaction(id)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun getRoleDataRedactions(role: DeveloperRole): Future<List<DataRedaction>> {
        log.trace { "Getting data redactions for role $role" }
        val promise = Promise.promise<List<DataRedaction>>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else {
                promise.complete(SourceStorage.getRoleDataRedactions(role).toList())
            }
        }
        return promise.future()
    }

    override fun addRoleDataRedaction(role: DeveloperRole, id: String): Future<Void> {
        log.trace { "Adding data redaction $id to role $role" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasDataRedaction(id)) {
                promise.fail(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                SourceStorage.addDataRedactionToRole(id, role)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun removeRoleDataRedaction(role: DeveloperRole, id: String): Future<Void> {
        log.trace { "Removing data redaction $id from role $role" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasDataRedaction(id)) {
                promise.fail(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                SourceStorage.removeDataRedactionFromRole(id, role)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun getDeveloperDataRedactions(developerId: String): Future<List<DataRedaction>> {
        log.trace { "Getting data redactions for developer $developerId" }
        val promise = Promise.promise<List<DataRedaction>>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                promise.complete(SourceStorage.getDeveloperDataRedactions(developerId).toList())
            }
        }
        return promise.future()
    }

    override fun getDeveloperAccessPermissions(developerId: String): Future<List<AccessPermission>> {
        log.trace { "Getting access permissions for developer $developerId" }
        val promise = Promise.promise<List<AccessPermission>>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                promise.complete(SourceStorage.getDeveloperAccessPermissions(developerId).toList())
            }
        }
        return promise.future()
    }

    override fun getClients(): Future<JsonObject> {
        log.trace { "Getting clients" }
        val promise = Promise.promise<JsonObject>()
        launch(vertx.dispatcher()) {
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
        log.trace { "Getting management stats" }
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

        launch(vertx.dispatcher()) {
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
                                SourceServices.LIVE_MANAGEMENT,
                                SourceStorage.counter(SourceServices.LIVE_MANAGEMENT).get().await()
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

        launch(vertx.dispatcher()) {
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

    override fun getServices(layer: String?): Future<List<Service>> {
        log.trace { "Getting services" }
        val promise = Promise.promise<List<Service>>()
        launch(vertx.dispatcher()) {
            val result = mutableListOf<Service>()
            val services = metadataQueryService.listServices(layer, null)
            services.forEach {
                result.add(
                    Service(
                        id = it.id,
                        name = it.name,
                        group = it.group,
                        shortName = it.shortName,
                        layers = it.layers.toList(),
                        normal = it.isNormal
                    )
                )
            }
            promise.complete(result)
        }
        return promise.future()
    }

    override fun getInstances(serviceId: String): Future<List<ServiceInstance>> {
        log.trace { "Getting instances for service $serviceId" }
        if (serviceId.isEmpty()) {
            return Future.failedFuture("Service id is empty")
        }

        val promise = Promise.promise<List<ServiceInstance>>()
        launch(vertx.dispatcher()) {
            val result = mutableListOf<ServiceInstance>()
            val duration = Duration().apply {
                start = "1111-01-01 1111"
                end = "2222-02-02 2222"
                step = Step.MINUTE
            }
            val instances = metadataQueryService.listInstances(duration, serviceId)
            instances.forEach {
                result.add(
                    ServiceInstance(
                        id = it.id,
                        name = it.name,
                        language = it.language.name,
                        instanceUUID = it.instanceUUID,
                        attributes = it.attributes.associate { attr -> attr.name to attr.value }
                    )
                )
            }
            promise.complete(result)
        }
        return promise.future()
    }

    override fun getEndpoints(serviceId: String): Future<List<ServiceEndpoint>> {
        log.trace { "Getting endpoints for service $serviceId" }
        val promise = Promise.promise<List<ServiceEndpoint>>()
        launch(vertx.dispatcher()) {
            val result = mutableListOf<ServiceEndpoint>()
            metadataQueryService.findEndpoint("", serviceId, 1000).forEach {
                result.add(
                    ServiceEndpoint(
                        id = it.id,
                        name = it.name
                    )
                )
            }
            promise.complete(result)
        }
        return promise.future()
    }

    override fun getActiveProbes(): Future<List<InstanceConnection>> {
        log.trace { "Getting active probes" }
        val promise = Promise.promise<List<InstanceConnection>>()
        launch(vertx.dispatcher()) {
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

    override fun getRolePermissions(role: DeveloperRole): Future<List<RolePermission>> {
        log.trace { "Getting role permissions" }
        val promise = Promise.promise<List<RolePermission>>()
        launch(vertx.dispatcher()) {
            try {
                val permissions = SourceStorage.getRolePermissions(role)
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
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getClientAccessors())
        }
        return promise.future()
    }

    override fun getClientAccess(id: String): Future<ClientAccess?> {
        log.trace { "Getting client access" }
        val promise = Promise.promise<ClientAccess?>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getClientAccess(id))
        }
        return promise.future()
    }

    override fun addClientAccess(): Future<ClientAccess> {
        log.trace { "Adding client access" }
        val promise = Promise.promise<ClientAccess>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.addClientAccess())
        }
        return promise.future()
    }

    override fun removeClientAccess(id: String): Future<Boolean> {
        log.trace { "Removing client access with id: $id" }
        val promise = Promise.promise<Boolean>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.removeClientAccess(id))
        }
        return promise.future()
    }

    override fun refreshClientAccess(id: String): Future<ClientAccess> {
        log.trace { "Refreshing client access with id: $id" }
        val promise = Promise.promise<ClientAccess>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.refreshClientAccess(id))
        }
        return promise.future()
    }

    override fun getAuthToken(accessToken: String): Future<String> {
        log.trace { "Getting auth token" }
        if (jwt == null) {
            return Future.failedFuture("JWT is not enabled")
        }

        val promise = Promise.promise<String>()
        launch(vertx.dispatcher()) {
            val dev = SourceStorage.getDeveloperByAccessToken(accessToken)
            if (dev != null) {
                val jwtToken = jwt.generateToken(
                    JsonObject().apply {
//                        if (!tenantId.isNullOrEmpty()) {
//                            put("tenant_id", tenantId)
//                        }
                    }
                        .put("developer_id", dev.id)
                        .put("created_at", Instant.now().toEpochMilli())
                        //todo: reasonable expires_at
                        .put("expires_at", Instant.now().plus(365, ChronoUnit.DAYS).toEpochMilli()),
                    JWTOptions().setAlgorithm("RS256")
                )
                promise.complete(jwtToken)
            } else {
                log.warn("Invalid token request. Token: {}", accessToken)
                promise.fail("Invalid token request")
            }
        }
        return promise.future()
    }

    override fun getDevelopers(): Future<List<Developer>> {
        log.trace { "Getting developers" }
        val promise = Promise.promise<List<Developer>>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getDevelopers())
        }
        return promise.future()
    }

    override fun addDeveloper(developerId: String): Future<Developer> {
        log.trace { "Adding developer with id: $developerId" }
        val promise = Promise.promise<Developer>()
        launch(vertx.dispatcher()) {
            if (SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Existing developer: $developerId"))
            } else {
                promise.complete(SourceStorage.addDeveloper(developerId))
            }
        }
        return promise.future()
    }

    override fun removeDeveloper(developerId: String): Future<Void> {
        log.trace { "Removing developer with id: $developerId" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (developerId == "system") {
                promise.fail(IllegalArgumentException("Unable to remove system developer"))
            } else if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                SourceStorage.removeDeveloper(developerId)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun refreshDeveloperToken(developerId: String): Future<Developer> {
        log.trace { "Refreshing developer token with id: $developerId" }
        val promise = Promise.promise<Developer>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                val newToken = RandomStringUtils.randomAlphanumeric(50)
                SourceStorage.setAccessToken(developerId, newToken)
                promise.complete(Developer(developerId, newToken))
            }
        }
        return promise.future()
    }

    override fun getRoles(): Future<List<DeveloperRole>> {
        log.trace { "Getting roles" }
        val promise = Promise.promise<List<DeveloperRole>>()
        launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getRoles().toList())
        }
        return promise.future()
    }

    override fun addRole(role: DeveloperRole): Future<Boolean> {
        log.trace { "Adding role with name: $role" }
        val promise = Promise.promise<Boolean>()
        launch(vertx.dispatcher()) {
            if (SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Existing role: $role"))
            } else {
                promise.complete(SourceStorage.addRole(role))
            }
        }
        return promise.future()
    }

    override fun removeRole(role: DeveloperRole): Future<Boolean> {
        log.trace { "Removing role with name: $role" }
        val promise = Promise.promise<Boolean>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (role.nativeRole) {
                promise.fail(IllegalArgumentException("Unable to remove native role"))
            } else {
                promise.complete(SourceStorage.removeRole(role))
            }
        }
        return promise.future()
    }

    override fun getDeveloperRoles(developerId: String): Future<List<DeveloperRole>> {
        log.trace { "Getting developer roles" }
        val promise = Promise.promise<List<DeveloperRole>>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                promise.complete(SourceStorage.getDeveloperRoles(developerId).toList())
            }
        }
        return promise.future()
    }

    override fun addDeveloperRole(developerId: String, role: DeveloperRole): Future<Void> {
        log.trace { "Adding role with id: ${role.roleName} to developer with id: $developerId" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: ${role.roleName}"))
            } else {
                SourceStorage.addRoleToDeveloper(developerId, role)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun removeDeveloperRole(developerId: String, role: DeveloperRole): Future<Void> {
        log.trace { "Removing role with id: ${role.roleName} from developer with id: $developerId" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasDeveloper(developerId)) {
                promise.fail(IllegalStateException("Non-existing developer: $developerId"))
            } else if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: ${role.roleName}"))
            } else {
                SourceStorage.removeRoleFromDeveloper(developerId, role)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun addRolePermission(role: DeveloperRole, permission: RolePermission): Future<Void> {
        log.trace { "Adding permission with id: ${permission.name} to role with id: ${role.roleName}" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (role.nativeRole) {
                promise.fail(IllegalArgumentException("Unable to update native role"))
            } else {
                SourceStorage.addPermissionToRole(role, permission)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun removeRolePermission(role: DeveloperRole, permission: RolePermission): Future<Void> {
        log.trace { "Removing permission with id: ${permission.name} from role with id: ${role.roleName}" }
        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            if (!SourceStorage.hasRole(role)) {
                promise.fail(IllegalStateException("Non-existing role: $role"))
            } else if (role.nativeRole) {
                promise.fail(IllegalArgumentException("Unable to update native role"))
            } else {
                SourceStorage.removePermissionFromRole(role, permission)
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun getDeveloperPermissions(developerId: String): Future<List<RolePermission>> {
        log.trace { "Getting developer permissions" }
        val promise = Promise.promise<List<RolePermission>>()
        launch(vertx.dispatcher()) {
            if (SourceStorage.hasDeveloper(developerId)) {
                promise.complete(SourceStorage.getDeveloperPermissions(developerId).toList())
            } else {
                promise.fail(IllegalStateException("Developer not found: $developerId"))
            }
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
        launch(vertx.dispatcher()) {
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

    override fun reset(): Future<Void> {
        log.trace { "Resetting" }
        val promise = Promise.promise<Void>()
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        launch(vertx.dispatcher()) {
            SourceStorage.reset()
            LiveInstrumentService.createProxy(vertx, devAuth.accessToken).clearAllLiveInstruments()
                .onSuccess { promise.complete() }
                .onFailure { promise.fail(it) }
        }
        return promise.future()
    }
}
