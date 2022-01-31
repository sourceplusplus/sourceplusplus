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
package spp.platform.core

import io.vertx.core.json.JsonObject
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.platform.core.storage.CoreStorage
import spp.protocol.auth.*
import spp.protocol.developer.Developer
import spp.protocol.utils.AccessChecker

object SourceStorage {

    private val log = LoggerFactory.getLogger(SourceStorage::class.java)

    lateinit var storage: CoreStorage
    lateinit var systemAccessToken: String

    suspend fun setup(storage: CoreStorage, config: JsonObject) {
        this.storage = storage

        //todo: if clustered, check if defaults are already set
        systemAccessToken = if (config.getJsonObject("spp-platform").getString("access_token").isNullOrEmpty()) {
            log.warn("No system access token provided. Using default: {}", "change-me")
            "change-me"
        } else {
            config.getJsonObject("spp-platform").getString("access_token")
        }
        installDefaults()
    }

    private suspend fun installDefaults() {
        //set default roles and permissions
        addRole(DeveloperRole.ROLE_MANAGER.roleName)
        addRole(DeveloperRole.ROLE_USER.roleName)
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
    }

    suspend fun reset(): Boolean {
        getDataRedactions().forEach { removeDataRedaction(it.id) }
        getRoles().forEach { removeRole(it) }
        getDevelopers().forEach { removeDeveloper(it.id) }
        installDefaults()
        return true
    }

    suspend fun getDevelopers(): List<Developer> {
        return storage.getDevelopers()
    }

    suspend fun getDeveloperByAccessToken(token: String): Developer? {
        return storage.getDeveloperByAccessToken(token)
    }

    suspend fun hasRole(roleName: String): Boolean {
        return storage.hasRole(roleName)
    }

    suspend fun removeRole(role: DeveloperRole): Boolean {
        return storage.removeRole(role)
    }

    suspend fun addRole(roleName: String): Boolean {
        return storage.addRole(roleName)
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

    suspend fun addDataRedaction(id: String, redactionPattern: String) {
        return storage.addDataRedaction(id, redactionPattern)
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
