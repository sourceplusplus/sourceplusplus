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

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.Counter
import io.vertx.core.shareddata.Lock
import io.vertx.core.shareddata.Shareable
import io.vertx.kotlin.coroutines.await
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer
import java.security.MessageDigest

open class MemoryStorage(val vertx: Vertx) : CoreStorage {

    override suspend fun counter(name: String): Counter {
        return vertx.sharedData().getCounter(namespace(name)).await()
    }

    override suspend fun lock(name: String): Lock {
        return vertx.sharedData().getLock(namespace(name)).await()
    }

    override suspend fun <K, V> map(name: String): AsyncMap<K, V> {
        return vertx.sharedData().getAsyncMap<K, V>(namespace(name)).await()
    }

    override suspend fun <T> get(name: String): T? {
        return map<String, T>("global.properties").get(name).await()
    }

    override suspend fun <T> put(name: String, value: T) {
        map<String, T>("global.properties").put(name, value).await()
    }

    override suspend fun getDevelopers(): List<Developer> {
        val currentDevelopers = vertx.sharedData().getAsyncMap<String, JsonArray>(namespace("developers"))
            .await().get("ids").await() ?: JsonArray()
        return currentDevelopers.list.map { Developer(it as String) }
    }

    override suspend fun getDeveloperByAccessToken(token: String): Developer? {
        return getDevelopers().find {
            //check access token with time-constant comparison
            MessageDigest.isEqual(getAccessToken(it.id).toByteArray(), token.toByteArray())
        }
    }

    override suspend fun hasRole(role: DeveloperRole): Boolean {
        val rolesStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("roles")).await()
        return (rolesStorage.get("roles").await() as JsonArray? ?: JsonArray())
            .list.find { it == role.roleName } != null
    }

    override suspend fun removeRole(role: DeveloperRole): Boolean {
        val currentRoles = vertx.sharedData().getAsyncMap<String, JsonArray>(namespace("roles"))
            .await().get("roles").await() ?: JsonArray()
        vertx.sharedData().getAsyncMap<String, JsonArray>(namespace("roles"))
            .await().put("roles", currentRoles.apply { remove(role.roleName) }).await()
        return true
    }

    override suspend fun addRole(role: DeveloperRole): Boolean {
        val currentRoles = vertx.sharedData().getAsyncMap<String, JsonArray>(namespace("roles"))
            .await().get("roles").await() ?: JsonArray()
        vertx.sharedData().getAsyncMap<String, JsonArray>(namespace("roles"))
            .await().put("roles", currentRoles.add(role.roleName)).await()
        return true
    }

    override suspend fun hasDeveloper(id: String): Boolean {
        val developersStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developers")).await()
        return (developersStorage.get("ids").await() as JsonArray? ?: JsonArray()).list.contains(id)
    }

    override suspend fun addDeveloper(id: String, token: String): Developer {
        val developersStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developers")).await()
        val currentDevelopers = developersStorage.get("ids").await() as JsonArray? ?: JsonArray()
        val existingDeveloper = currentDevelopers.list.find { it == id } as String?
        if (existingDeveloper != null) error("Developer $existingDeveloper already exists")
        currentDevelopers.add(id)
        developersStorage.put("ids", currentDevelopers).await()

        setAccessToken(id, token)
        return Developer(id, token)
    }

    override suspend fun removeDeveloper(id: String) {
        val developersStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developers")).await()
        val currentDevelopers = developersStorage.get("ids").await() as JsonArray? ?: JsonArray()
        currentDevelopers.list.remove(id)
        developersStorage.put("ids", currentDevelopers).await()

        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developer:$id")).await()
        developerStorage.clear().await()
    }

    override suspend fun setAccessToken(id: String, accessToken: String) {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developer:$id")).await()
        developerStorage.put("accessToken", accessToken).await()
    }

    private suspend fun getAccessToken(developerId: String): String {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developer:$developerId")).await()
        return developerStorage.get("accessToken").await() as String
    }

    override suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("developer:$developerId")).await()
        val roles = developerStorage.get("roles").await() as JsonArray? ?: JsonArray()
        return roles.list.map { it as DeveloperRole }
    }

    override suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("role:${role.roleName}")).await()
        val accessPermissions = roleStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return accessPermissions.list.map { getAccessPermission(it as String) }.toSet()
    }

    override suspend fun getAccessPermissions(): Set<AccessPermission> {
        val accessPermissionsStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermissions")).await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return access.list.map { it as AccessPermission }.toSet()
    }

    override suspend fun hasAccessPermission(id: String): Boolean {
        val accessPermissionsStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermissions")).await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return access.list.find { (it as AccessPermission).id == id } != null
    }

    override suspend fun getAccessPermission(id: String): AccessPermission {
        val accessPermissionsStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermissions")).await()
        val accessPermissions = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return accessPermissions.list.find { (it as AccessPermission).id == id } as AccessPermission
    }

    override suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        val accessPermissionsStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermissions")).await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        access.add(AccessPermission(id, locationPatterns, type))
        accessPermissionsStorage.put("accessPermissions", access).await()

        val accessPermissionStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermission:$id")).await()
        accessPermissionStorage.put("locationPatterns", JsonArray(locationPatterns)).await()
        accessPermissionStorage.put("type", type.name).await()
    }

    override suspend fun removeAccessPermission(id: String) {
        val accessPermissionsStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermissions")).await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        access.list.removeIf { (it as AccessPermission).id == id }
        accessPermissionsStorage.put("accessPermissions", access).await()

        val accessPermissionStorage =
            vertx.sharedData().getAsyncMap<String, Any>(namespace("accessPermission:$id")).await()
        accessPermissionStorage.clear().await()
    }

    override suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("role:${role.roleName}")).await()
        val accessPermissions = roleStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        if (accessPermissions.list.find { it == id } == null) {
            accessPermissions.add(id)
            roleStorage.put("accessPermissions", accessPermissions).await()
        }
    }

    override suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("role:${role.roleName}")).await()
        val accessPermissions = roleStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        accessPermissions.list.removeIf { it == id }
        roleStorage.put("accessPermissions", accessPermissions).await()
    }

    override suspend fun getDataRedactions(): Set<DataRedaction> {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("dataRedactions")).await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.map { it as DataRedaction }.toSet()
    }

    override suspend fun hasDataRedaction(id: String): Boolean {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("dataRedactions")).await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.find { (it as DataRedaction).id == id } != null
    }

    override suspend fun getDataRedaction(id: String): DataRedaction {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("dataRedactions")).await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.find { (it as DataRedaction).id == id } as DataRedaction
    }

    override suspend fun addDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("dataRedactions")).await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.add(DataRedaction(id, type, lookup, replacement))
        dataRedactionsStorage.put("dataRedactions", dataRedactions).await()
    }

    override suspend fun updateDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("dataRedactions")).await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.list.removeIf { (it as DataRedaction).id == id }

        dataRedactions.add(DataRedaction(id, type, lookup, replacement))
        dataRedactionsStorage.put("dataRedactions", dataRedactions).await()
    }

    override suspend fun removeDataRedaction(id: String) {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("dataRedactions")).await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.list.removeIf { (it as DataRedaction).id == id }
        dataRedactionsStorage.put("dataRedactions", dataRedactions).await()
    }

    override suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        val dataRedaction = getDataRedaction(id)
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("role:${role.roleName}")).await()
        val dataRedactions = roleStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        if (dataRedactions.list.find { (it as String) == id } == null) {
            dataRedactions.add(dataRedaction.id)
            roleStorage.put("dataRedactions", dataRedactions).await()
        }
    }

    override suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("role:${role.roleName}")).await()
        val dataRedactions = roleStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.list.removeIf { (it as String) == id }
        roleStorage.put("dataRedactions", dataRedactions).await()
    }

    override suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("role:${role.roleName}")).await()
        val dataRedactions = roleStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.map { getDataRedaction(it as String) }.toSet()
    }

    override suspend fun getRoles(): Set<DeveloperRole> {
        val rolesStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("roles")).await()
        return (rolesStorage.get("roles").await() as JsonArray? ?: JsonArray())
            .list.map { DeveloperRole.fromString(it as String) }.toSet()
    }

    override suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Shareable>(namespace("developer:$id")).await()
        val devRoles = developerStorage.get("roles").await() as JsonArray? ?: JsonArray()
        val existingRole = devRoles.list.find { (it as DeveloperRole) == role } as DeveloperRole?
        if (existingRole == null) {
            devRoles.add(role)
            developerStorage.put("roles", devRoles).await()
        }
    }

    override suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Shareable>(namespace("developer:$id")).await()
        val devRoles = developerStorage.get("roles").await() as JsonArray? ?: JsonArray()
        val existingRole = devRoles.list.find { (it as DeveloperRole) == role } as DeveloperRole?
        if (existingRole != null) {
            devRoles.remove(existingRole)
            developerStorage.put("roles", devRoles).await()
        }
    }

    override suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Shareable>(namespace("role:${role.roleName}")).await()
        val rolePermissions = roleStorage.get("permissions").await() as JsonArray? ?: JsonArray()
        val existingPermission = rolePermissions.list.find { (it as RolePermission) == permission } as RolePermission?
        if (existingPermission == null) {
            rolePermissions.add(permission)
            roleStorage.put("permissions", rolePermissions).await()
        }
    }

    override suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Shareable>(namespace("role:${role.roleName}")).await()
        val rolePermissions = roleStorage.get("permissions").await() as JsonArray? ?: JsonArray()
        val existingPermission = rolePermissions.list.find { (it as RolePermission) == permission } as RolePermission?
        if (existingPermission != null) {
            rolePermissions.remove(existingPermission)
            roleStorage.put("permissions", rolePermissions).await()
        }
    }

    override suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Shareable>(namespace("role:${role.roleName}")).await()
        val rolePermissions = roleStorage.get("permissions").await() as JsonArray? ?: JsonArray()
        return rolePermissions.list.map { it as RolePermission }.toSet()
    }

    override suspend fun getClientAccessors(): List<ClientAccess> {
        val clientAccessStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("client_access")).await()
        return (clientAccessStorage.get("client_accessors").await() as JsonArray? ?: JsonArray())
            .list.map { ClientAccess(JsonObject(it.toString())) }
    }

    override suspend fun getClientAccess(id: String): ClientAccess? {
        val clientAccessors = getClientAccessors()
        return clientAccessors.find {
            //check secret with time-constant comparison
            MessageDigest.isEqual(it.id.toByteArray(), id.toByteArray())
        }
    }

    override suspend fun addClientAccess(id: String?, secret: String?): ClientAccess {
        val clientAccessStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("client_access")).await()
        val clientAccessors = clientAccessStorage.get("client_accessors").await() as JsonArray? ?: JsonArray()
        val clientAccess = generateClientAccess(id, secret)
        clientAccessors.add(JsonObject.mapFrom(clientAccess))
        clientAccessStorage.put("client_accessors", clientAccessors).await()
        return clientAccess
    }

    override suspend fun removeClientAccess(id: String): Boolean {
        val clientAccessStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("client_access")).await()
        val clientAccessors = (clientAccessStorage.get("client_accessors").await() as JsonArray? ?: JsonArray())
        val existingClientAccess = clientAccessors.find { JsonObject.mapFrom(it).getString("id") == id }
        if (existingClientAccess != null) {
            clientAccessors.remove(existingClientAccess)
            clientAccessStorage.put("client_accessors", clientAccessors).await()
            return true
        }
        return false
    }

    override suspend fun refreshClientAccess(id: String): ClientAccess {
        val clientAccessStorage = vertx.sharedData().getAsyncMap<String, Any>(namespace("client_access")).await()
        val clientAccessors = clientAccessStorage.get("client_accessors").await() as JsonArray? ?: JsonArray()
        val existingClientAccess = clientAccessors.list.find { (it as JsonObject).getString("id") == id } as JsonObject?
        if (existingClientAccess != null) {
            val clientAccess = generateClientAccess(id, generateClientSecret())
            clientAccessors.remove(existingClientAccess)
            clientAccessors.add(JsonObject.mapFrom(clientAccess))
            clientAccessStorage.put("client_accessors", clientAccessors).await()
            return clientAccess
        }
        throw IllegalArgumentException("Client access with id $id does not exist")
    }
}
