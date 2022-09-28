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
package spp.platform.storage

import com.google.common.base.CaseFormat
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.Counter
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.SessionStore
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection.config
import spp.platform.common.ClusterConnection.getVertx
import spp.protocol.instrument.LiveInstrument
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer
import spp.protocol.service.error.PermissionAccessDenied
import java.util.concurrent.CompletableFuture

object SourceStorage {

    private const val DEFAULT_ACCESS_TOKEN = "change-me"
    private val log = LoggerFactory.getLogger(SourceStorage::class.java)

    lateinit var storage: CoreStorage
    lateinit var sessionStore: SessionStore
    lateinit var sessionHandler: SessionHandler

    fun getStorageConfig(): JsonObject {
        val storageSelector = config.getJsonObject("storage").getString("selector")
        val storageName = CaseFormat.LOWER_CAMEL.to(
            CaseFormat.LOWER_HYPHEN,
            storageSelector.substringAfterLast(".").removeSuffix("Storage")
        )
        return config.getJsonObject("storage").getJsonObject(storageName) ?: JsonObject()
    }

    fun initSessionStore(sessionStore: SessionStore) {
        this.sessionStore = sessionStore
        this.sessionHandler = SessionHandler.create(SourceStorage.sessionStore)
    }

    suspend fun setup(storage: CoreStorage) {
        SourceStorage.storage = storage

        val installDefaults = getStorageConfig().getString("install_defaults")?.toBooleanStrictOrNull() != false
        if (installDefaults) {
            installDefaults()
        }
    }

    private suspend fun installDefaults() {
        val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
        val accessToken = jwtConfig.getString("access_token")
        val systemAccessToken = if (accessToken.isNullOrEmpty()) DEFAULT_ACCESS_TOKEN else accessToken
        if (systemAccessToken == DEFAULT_ACCESS_TOKEN) {
            log.warn("Using default system access token. This is not recommended.")
        }

        installDefaults(systemAccessToken)
    }

    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun installDefaults(systemAccessToken: String) {
        put("system_access_token", systemAccessToken)

        //set default roles, permissions, redactions
        log.info("Installing default roles, permissions, and redactions")
        addRole(DeveloperRole.ROLE_MANAGER)
        addRole(DeveloperRole.ROLE_USER)
        addDeveloper("system", get("system_access_token", DEFAULT_ACCESS_TOKEN))
        addRoleToDeveloper("system", DeveloperRole.ROLE_MANAGER)
        RolePermission.values().forEach {
            addPermissionToRole(DeveloperRole.ROLE_MANAGER, it)
        }
        RolePermission.values().forEach {
            if (!it.manager) {
                addPermissionToRole(DeveloperRole.ROLE_USER, it)
            }
        }

        //set default redactions
        val piiRedaction = config.getJsonObject("spp-platform").getJsonObject("pii-redaction")
        if (piiRedaction.getString("enabled").toBoolean()) {
            piiRedaction.getJsonArray("redactions").list.map {
                JsonObject.mapFrom(it).let {
                    DataRedaction(
                        it.getString("id"),
                        RedactionType.valueOf(it.getString("type")),
                        it.getString("lookup"),
                        it.getString("replacement")
                    )
                }
            }
        } else {
            emptyList()
        }.forEach { addDataRedaction(it.id, it.type, it.lookup, it.replacement) }

        //set default client accessors
        val clientAccessors = config.getJsonObject("client-access")
        if (clientAccessors?.getString("enabled").toBoolean()) {
            clientAccessors?.getJsonArray("accessors")?.list?.map {
                JsonObject.mapFrom(it).let {
                    if (!it.getString("id").isNullOrEmpty() && !it.getString("secret").isNullOrEmpty()) {
                        ClientAccess(it.getString("id"), it.getString("secret"))
                    } else {
                        null
                    }
                }
            }.orEmpty().filterNotNull()
        } else {
            emptyList()
        }.forEach { addClientAccess(it.id, it.secret) }
    }

    suspend fun reset(): Boolean {
        getDataRedactions().forEach { removeDataRedaction(it.id) }
        getAccessPermissions().forEach { removeAccessPermission(it.id) }
        getRoles().forEach { removeRole(it) }
        getDevelopers().forEach { removeDeveloper(it.id) }
        getClientAccessors().forEach { removeClientAccess(it.id) }
        getLiveInstruments().forEach { removeLiveInstrument(it.id!!) }
        installDefaults(get("system_access_token", DEFAULT_ACCESS_TOKEN))
        return true
    }

    suspend fun counter(name: String): Counter {
        return storage.counter(name)
    }

    suspend fun <K, V> map(name: String): AsyncMap<K, V> {
        return storage.map(name)
    }

    suspend fun <T> get(name: String): T? {
        return storage.get(name)
    }

    suspend fun <T> get(name: String, default: T): T {
        return get<T>(name) ?: default
    }

    suspend fun put(name: String, value: Any) {
        storage.put(name, value)
    }

    suspend fun getClientAccessors(): List<ClientAccess> {
        return storage.getClientAccessors()
    }

    suspend fun getClientAccess(id: String): ClientAccess? {
        return storage.getClientAccess(id)
    }

    suspend fun addClientAccess(id: String? = null, secret: String? = null): ClientAccess {
        return storage.addClientAccess(id, secret)
    }

    suspend fun removeClientAccess(id: String): Boolean {
        return storage.removeClientAccess(id)
    }

    suspend fun refreshClientAccess(id: String): ClientAccess {
        return storage.refreshClientAccess(id)
    }

    suspend fun getDevelopers(): List<Developer> {
        return storage.getDevelopers()
    }

    suspend fun getDeveloperByAccessToken(token: String): Developer? {
        return storage.getDeveloperByAccessToken(token)
    }

    suspend fun hasRole(role: DeveloperRole): Boolean {
        return storage.hasRole(role)
    }

    suspend fun removeRole(role: DeveloperRole): Boolean {
        return storage.removeRole(role)
    }

    suspend fun addRole(role: DeveloperRole): Boolean {
        return storage.addRole(role)
    }

    suspend fun hasDeveloper(id: String): Boolean {
        return storage.hasDeveloper(id)
    }

    suspend fun addDeveloper(id: String): Developer {
        return addDeveloper(id, null)
    }

    suspend fun addDeveloper(id: String, token: String?): Developer {
        return storage.addDeveloper(id, token ?: RandomStringUtils.randomAlphanumeric(50))
    }

    suspend fun removeDeveloper(id: String) {
        return storage.removeDeveloper(id)
    }

    suspend fun setAccessToken(id: String, accessToken: String) {
        return storage.setAccessToken(id, accessToken)
    }

    suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        return storage.getDeveloperRoles(developerId)
    }

    suspend fun getDeveloperAccessPermissions(developerId: String): List<AccessPermission> {
        return getDeveloperRoles(developerId).flatMap { getRoleAccessPermissions(it) }
    }

    suspend fun hasInstrumentAccess(developerId: String, locationPattern: String): Boolean {
        val permissions = getDeveloperAccessPermissions(developerId)
        return AccessChecker.hasInstrumentAccess(permissions, locationPattern)
    }

    suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        return storage.getRoleAccessPermissions(role)
    }

    suspend fun getAccessPermissions(): Set<AccessPermission> {
        return storage.getAccessPermissions()
    }

    suspend fun hasAccessPermission(id: String): Boolean {
        return storage.hasAccessPermission(id)
    }

    suspend fun getAccessPermission(id: String): AccessPermission {
        return storage.getAccessPermission(id)
    }

    suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        return storage.addAccessPermission(id, locationPatterns, type)
    }

    suspend fun removeAccessPermission(id: String) {
        return storage.removeAccessPermission(id)
    }

    suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        return storage.addAccessPermissionToRole(id, role)
    }

    suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        return storage.removeAccessPermissionFromRole(id, role)
    }

    suspend fun getDeveloperDataRedactions(developerId: String): List<DataRedaction> {
        return getDeveloperRoles(developerId).flatMap { getRoleDataRedactions(it) }
    }

    suspend fun getDataRedactions(): Set<DataRedaction> {
        return storage.getDataRedactions()
    }

    suspend fun hasDataRedaction(id: String): Boolean {
        return storage.hasDataRedaction(id)
    }

    suspend fun getDataRedaction(id: String): DataRedaction {
        return storage.getDataRedaction(id)
    }

    suspend fun addDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        return storage.addDataRedaction(id, type, lookup, replacement)
    }

    suspend fun updateDataRedaction(id: String, type: RedactionType?, lookup: String?, replacement: String?) {
        val existingDataRedaction = storage.getDataRedaction(id)
        return storage.updateDataRedaction(
            id,
            type ?: existingDataRedaction.type,
            lookup ?: existingDataRedaction.lookup,
            replacement ?: existingDataRedaction.replacement
        )
    }

    suspend fun removeDataRedaction(id: String) {
        return storage.removeDataRedaction(id)
    }

    suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        return storage.addDataRedactionToRole(id, role)
    }

    suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        return storage.removeDataRedactionFromRole(id, role)
    }

    suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        return storage.getRoleDataRedactions(role)
    }

    suspend fun getRoles(): Set<DeveloperRole> {
        return storage.getRoles()
    }

    suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        return storage.addRoleToDeveloper(id, role)
    }

    suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        return storage.removeRoleFromDeveloper(id, role)
    }

    suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        return storage.addPermissionToRole(role, permission)
    }

    suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        return storage.removePermissionFromRole(role, permission)
    }

    suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        return storage.getRolePermissions(role)
    }

    suspend fun getDeveloperPermissions(id: String): Set<RolePermission> {
        return getDeveloperRoles(id).flatMap { getRolePermissions(it) }.toSet()
    }

    suspend fun hasPermission(id: String, permission: RolePermission): Boolean {
        log.trace("Checking permission: $permission - Id: $id")
        return getDeveloperRoles(id).any { getRolePermissions(it).contains(permission) }
    }

    suspend fun requiresPermission(
        id: String,
        permission: RolePermission,
        completableFuture: CompletableFuture<*>
    ): Boolean {
        return if (hasPermission(id, permission)) {
            false
        } else {
            completableFuture.completeExceptionally(
                PermissionAccessDenied(permission, "Developer '$id' missing permission: $permission")
            )
            true
        }
    }

    fun isValidClientAccess(clientId: String, clientSecret: String?): Future<Void> {
        val promise = Promise.promise<Void>()
        val authEnabled = config.getJsonObject("client-access")?.getString("enabled")?.toBooleanStrictOrNull()
        if (authEnabled == true) {
            GlobalScope.launch(getVertx().dispatcher()) {
                if (storage.getClientAccess(clientId)?.secret == clientSecret) {
                    promise.complete()
                } else {
                    promise.fail("Invalid client secret")
                }
            }
        } else {
            promise.complete()
        }
        return promise.future()
    }

    /**
     * Add a new [LiveInstrument] to the platform.
     */
    suspend fun addLiveInstrument(instrument: LiveInstrument): LiveInstrument {
        return storage.addLiveInstrument(instrument)
    }

    /**
     * Update an existing [LiveInstrument] on the platform with the given id.
     */
    suspend fun updateLiveInstrument(id: String, instrument: LiveInstrument): LiveInstrument {
        return storage.updateLiveInstrument(id, instrument)
    }

    /**
     * Remove the [LiveInstrument] with the given id.
     */
    suspend fun removeLiveInstrument(id: String): Boolean {
        return storage.removeLiveInstrument(id)
    }

    /**
     * Get the [LiveInstrument] with the given id.
     */
    suspend fun getLiveInstrument(id: String): LiveInstrument? {
        return storage.getLiveInstrument(id)
    }

    /**
     * Retrieve all [LiveInstrument]s.
     */
    suspend fun getLiveInstruments(): List<LiveInstrument> {
        return storage.getLiveInstruments()
    }

    /**
     * Retrieve all [LiveInstrument]s where [LiveInstrument.pending] is true.
     */
    suspend fun getPendingLiveInstruments(): List<LiveInstrument> {
        return storage.getPendingLiveInstruments()
    }
}
