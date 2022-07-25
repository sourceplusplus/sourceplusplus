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

import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.Counter
import io.vertx.ext.web.handler.SessionHandler
import io.vertx.ext.web.sstore.SessionStore
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer

object SourceStorage {

    private val log = LoggerFactory.getLogger(SourceStorage::class.java)

    lateinit var storage: CoreStorage
    lateinit var systemAccessToken: String //todo: multi tenant, can't be global
    private lateinit var systemRedactors: List<DataRedaction>
    lateinit var sessionStore: SessionStore
    lateinit var sessionHandler: SessionHandler

    fun initSessionStore(sessionStore: SessionStore) {
        this.sessionStore = sessionStore
        this.sessionHandler = SessionHandler.create(SourceStorage.sessionStore)
    }

    suspend fun setup(storage: CoreStorage, config: JsonObject, installDefaults: Boolean = true) {
        SourceStorage.storage = storage

        val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
        val accessToken = jwtConfig.getString("access_token")

        //todo: if clustered, check if defaults are already set
        systemAccessToken = if (accessToken.isNullOrEmpty()) {
            log.warn("No system access token provided. Using default: {}", "change-me")
            "change-me"
        } else {
            accessToken
        }

        val piiRedaction = config.getJsonObject("spp-platform").getJsonObject("pii-redaction")
        systemRedactors = if (piiRedaction.getString("enabled").toBoolean()) {
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
        }

        if (installDefaults) {
            installDefaults()
        }
    }

    private suspend fun installDefaults() {
        //set default roles, permissions, redactions
        log.info("Installing default roles, permissions, and redactions")
        addRole(DeveloperRole.ROLE_MANAGER)
        addRole(DeveloperRole.ROLE_USER)
        addDeveloper("system", systemAccessToken)
        addRoleToDeveloper("system", DeveloperRole.ROLE_MANAGER)
        RolePermission.values().forEach {
            addPermissionToRole(DeveloperRole.ROLE_MANAGER, it)
        }
        RolePermission.values().forEach {
            if (!it.manager) {
                addPermissionToRole(DeveloperRole.ROLE_USER, it)
            }
        }
        systemRedactors.forEach { addDataRedaction(it.id, it.type, it.lookup, it.replacement) }
    }

    suspend fun reset(): Boolean {
        getDataRedactions().forEach { removeDataRedaction(it.id) }
        getAccessPermissions().forEach { removeAccessPermission(it.id) }
        getRoles().forEach { removeRole(it) }
        getDevelopers().forEach { removeDeveloper(it.id) }
        installDefaults()
        return true
    }

    suspend fun counter(name: String): Counter {
        return storage.counter(name)
    }

    suspend fun <K, V> map(name: String): AsyncMap<K, V> {
        return storage.map(name)
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
        return addDeveloper(id, RandomStringUtils.randomAlphanumeric(50))
    }

    suspend fun addDeveloper(id: String, token: String): Developer {
        return storage.addDeveloper(id, token)
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
}
