package spp.platform.core.storage

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.shareddata.Shareable
import io.vertx.kotlin.coroutines.await
import spp.protocol.auth.*
import spp.protocol.developer.Developer

class MemoryStorage(val vertx: Vertx) : CoreStorage {

    override suspend fun getDevelopers(): List<Developer> {
        val currentDevelopers = vertx.sharedData().getAsyncMap<String, JsonArray>("developers")
            .await().get("ids").await() ?: JsonArray()
        return currentDevelopers.list.map { Developer(it as String) }
    }

    override suspend fun getDeveloperByAccessToken(token: String): Developer? {
        return getDevelopers().find { getAccessToken(it.id) == token }
    }

    override suspend fun hasRole(roleName: String): Boolean {
        val rolesStorage = vertx.sharedData().getAsyncMap<String, Any>("roles").await()
        return (rolesStorage.get("roles").await() as JsonArray? ?: JsonArray()).list.find { it == roleName } != null
    }

    override suspend fun removeRole(role: DeveloperRole): Boolean {
        val currentRoles = vertx.sharedData().getAsyncMap<String, JsonArray>("roles")
            .await().get("roles").await() ?: JsonArray()
        vertx.sharedData().getAsyncMap<String, JsonArray>("roles")
            .await().put("roles", currentRoles.apply { remove(role.roleName) })
        return true
    }

    override suspend fun addRole(roleName: String): Boolean {
        val currentRoles = vertx.sharedData().getAsyncMap<String, JsonArray>("roles")
            .await().get("roles").await() ?: JsonArray()
        vertx.sharedData().getAsyncMap<String, JsonArray>("roles")
            .await().put("roles", currentRoles.add(roleName))
        return true
    }

    override suspend fun hasDeveloper(id: String): Boolean {
        val developersStorage = vertx.sharedData().getAsyncMap<String, Any>("developers").await()
        return (developersStorage.get("ids").await() as JsonArray? ?: JsonArray()).list.contains(id)
    }

    override suspend fun addDeveloper(id: String, token: String): Developer {
        val developersStorage = vertx.sharedData().getAsyncMap<String, Any>("developers").await()
        val currentDevelopers = developersStorage.get("ids").await() as JsonArray? ?: JsonArray()
        val existingDeveloper = currentDevelopers.list.find { it == id } as String?
        if (existingDeveloper != null) throw IllegalStateException("Developer $existingDeveloper already exists")
        currentDevelopers.add(id)
        developersStorage.put("ids", currentDevelopers)

        setAccessToken(id, token)
        return Developer(id, token)
    }

    override suspend fun removeDeveloper(id: String) {
        val developersStorage = vertx.sharedData().getAsyncMap<String, Any>("developers").await()
        val currentDevelopers = developersStorage.get("ids").await() as JsonArray? ?: JsonArray()
        currentDevelopers.list.remove(id)
        developersStorage.put("ids", currentDevelopers)

        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>("developer:$id").await()
        developerStorage.clear().await()
    }

    override suspend fun setAccessToken(id: String, accessToken: String) {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>("developer:$id").await()
        developerStorage.put("accessToken", accessToken).await()
    }

    private suspend fun getAccessToken(developerId: String): String {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>("developer:$developerId").await()
        return developerStorage.get("accessToken").await() as String
    }

    override suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Any>("developer:$developerId").await()
        val roles = developerStorage.get("roles").await() as JsonArray? ?: JsonArray()
        return roles.list.map { it as DeveloperRole }
    }

    override suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>("role:${role.roleName}").await()
        val accessPermissions = roleStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return accessPermissions.list.map { getAccessPermission(it as String) }.toSet()
    }

    override suspend fun getAccessPermissions(): Set<AccessPermission> {
        val accessPermissionsStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermissions").await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return access.list.map { it as AccessPermission }.toSet()
    }

    override suspend fun hasAccessPermission(id: String): Boolean {
        val accessPermissionsStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermissions").await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return access.list.find { (it as AccessPermission).id == id } != null
    }

    override suspend fun getAccessPermission(id: String): AccessPermission {
        val accessPermissionsStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermissions").await()
        val accessPermissions = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        return accessPermissions.list.find { (it as AccessPermission).id == id } as AccessPermission
    }

    override suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        val accessPermissionsStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermissions").await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        access.add(AccessPermission(id, locationPatterns, type))
        accessPermissionsStorage.put("accessPermissions", access)

        val accessPermissionStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermission:$id").await()
        accessPermissionStorage.put("locationPatterns", JsonArray(locationPatterns)).await()
        accessPermissionStorage.put("type", type.name).await()
    }

    override suspend fun removeAccessPermission(id: String) {
        val accessPermissionsStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermissions").await()
        val access = accessPermissionsStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        access.list.removeIf { (it as AccessPermission).id == id }
        accessPermissionsStorage.put("accessPermissions", access)

        val accessPermissionStorage = vertx.sharedData().getAsyncMap<String, Any>("accessPermission:$id").await()
        accessPermissionStorage.clear().await()
    }

    override suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>("role:${role.roleName}").await()
        val accessPermissions = roleStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        if (accessPermissions.list.find { it == id } == null) {
            accessPermissions.add(id)
            roleStorage.put("accessPermissions", accessPermissions)
        }
    }

    override suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>("role:${role.roleName}").await()
        val accessPermissions = roleStorage.get("accessPermissions").await() as JsonArray? ?: JsonArray()
        accessPermissions.list.removeIf { it == id }
        roleStorage.put("accessPermissions", accessPermissions)
    }

    override suspend fun getDataRedactions(): Set<DataRedaction> {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedactions").await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.map { it as DataRedaction }.toSet()
    }

    override suspend fun hasDataRedaction(id: String): Boolean {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedactions").await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.find { (it as DataRedaction).id == id } != null
    }

    override suspend fun getDataRedaction(id: String): DataRedaction {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedactions").await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.find { (it as DataRedaction).id == id } as DataRedaction
    }

    override suspend fun addDataRedaction(id: String, redactionPattern: String) {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedactions").await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.add(DataRedaction(id, redactionPattern))
        dataRedactionsStorage.put("dataRedactions", dataRedactions)

        val dataRedactionStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedaction:$id").await()
        dataRedactionStorage.put("redactionPattern", redactionPattern).await()
    }

    override suspend fun removeDataRedaction(id: String) {
        val dataRedactionsStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedactions").await()
        val dataRedactions = dataRedactionsStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.list.removeIf { (it as DataRedaction).id == id }
        dataRedactionsStorage.put("dataRedactions", dataRedactions)

        val dataRedactionStorage = vertx.sharedData().getAsyncMap<String, Any>("dataRedaction:$id").await()
        dataRedactionStorage.clear().await()
    }

    override suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        val dataRedaction = getDataRedaction(id)
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>("role:${role.roleName}").await()
        val dataRedactions = roleStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        if (dataRedactions.list.find { (it as DataRedaction).id == id } == null) {
            dataRedactions.add(dataRedaction)
            roleStorage.put("dataRedactions", dataRedactions)
        }
    }

    override suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>("role:${role.roleName}").await()
        val dataRedactions = roleStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        dataRedactions.list.removeIf { (it as DataRedaction).id == id }
        roleStorage.put("dataRedactions", dataRedactions)
    }

    override suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Any>("role:${role.roleName}").await()
        val dataRedactions = roleStorage.get("dataRedactions").await() as JsonArray? ?: JsonArray()
        return dataRedactions.list.map { it as DataRedaction }.toSet()
    }

    override suspend fun getRoles(): Set<DeveloperRole> {
        val rolesStorage = vertx.sharedData().getAsyncMap<String, Any>("roles").await()
        return (rolesStorage.get("roles").await() as JsonArray? ?: JsonArray())
            .list.map { DeveloperRole.fromString(it as String) }.toSet()
    }

    override suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Shareable>("developer:$id").await()
        val devRoles = developerStorage.get("roles").await() as JsonArray? ?: JsonArray()
        val existingRole = devRoles.list.find { (it as DeveloperRole) == role } as DeveloperRole?
        if (existingRole == null) {
            devRoles.add(role)
            developerStorage.put("roles", devRoles)
        }
    }

    override suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        val developerStorage = vertx.sharedData().getAsyncMap<String, Shareable>("developer:$id").await()
        val devRoles = developerStorage.get("roles").await() as JsonArray? ?: JsonArray()
        val existingRole = devRoles.list.find { (it as DeveloperRole) == role } as DeveloperRole?
        if (existingRole != null) {
            devRoles.remove(existingRole)
            developerStorage.put("roles", devRoles)
        }
    }

    override suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Shareable>("role:${role.roleName}").await()
        val rolePermissions = roleStorage.get("permissions").await() as JsonArray? ?: JsonArray()
        val existingPermission = rolePermissions.list.find { (it as RolePermission) == permission } as RolePermission?
        if (existingPermission == null) {
            rolePermissions.add(permission)
            roleStorage.put("permissions", rolePermissions)
        }
    }

    override suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Shareable>("role:${role.roleName}").await()
        val rolePermissions = roleStorage.get("permissions").await() as JsonArray? ?: JsonArray()
        val existingPermission = rolePermissions.list.find { (it as RolePermission) == permission } as RolePermission?
        if (existingPermission != null) {
            rolePermissions.remove(existingPermission)
            roleStorage.put("permissions", rolePermissions)
        }
    }

    override suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val roleStorage = vertx.sharedData().getAsyncMap<String, Shareable>("role:${role.roleName}").await()
        val rolePermissions = roleStorage.get("permissions").await() as JsonArray? ?: JsonArray()
        return rolePermissions.list.map { it as RolePermission }.toSet()
    }
}
