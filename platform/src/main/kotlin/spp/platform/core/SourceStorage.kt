package spp.platform.core

import com.sourceplusplus.protocol.developer.Developer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.platform.SourcePlatform
import spp.platform.core.auth.*
import java.nio.charset.StandardCharsets

object SourceStorage {

    private val log = LoggerFactory.getLogger(SourceStorage::class.java)

    suspend fun installDefaults() {
        //set default roles and permissions
        addRole(DeveloperRole.ROLE_MANAGER.roleName)
        addRole(DeveloperRole.ROLE_USER.roleName)
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
        val devIds = SourcePlatform.redis.smembers("developers:ids").await()
        return devIds.map { Developer(it.toString(StandardCharsets.UTF_8)) }
    }

    suspend fun getDeveloperByAccessToken(token: String): Developer? {
        val devId = SourcePlatform.redis.get("developers:access_tokens:$token").await()
            ?.toString(StandardCharsets.UTF_8) ?: return null
        return Developer(devId)
    }

    suspend fun hasAccessToken(token: String): Boolean {
        return SourcePlatform.redis.sismember("developers:access_tokens", token).await().toBoolean()
    }

    suspend fun hasRole(roleName: String): Boolean {
        val role = DeveloperRole.fromString(roleName)
        return SourcePlatform.redis.sismember("roles", role.roleName).await().toBoolean()
    }

    suspend fun removeRole(role: DeveloperRole): Boolean {
        getRolePermissions(role).forEach {
            removePermissionFromRole(role, it)
        }
        return SourcePlatform.redis.srem(listOf("roles", role.roleName)).await().toBoolean()
    }

    suspend fun addRole(roleName: String): Boolean {
        val role = DeveloperRole.fromString(roleName)
        return SourcePlatform.redis.sadd(listOf("roles", role.roleName)).await().toBoolean()
    }

    suspend fun hasDeveloper(id: String): Boolean {
        return SourcePlatform.redis.sismember("developers:ids", id).await().toBoolean()
    }

    suspend fun addDeveloper(id: String): Developer {
        return addDeveloper(id, RandomStringUtils.randomAlphanumeric(50))
    }

    suspend fun addDeveloper(id: String, token: String): Developer {
        SourcePlatform.redis.sadd(listOf("developers:ids", id)).await()
        SourcePlatform.redis.set(listOf("developers:access_tokens:$token", id)).await()
        SourcePlatform.redis.sadd(listOf("developers:access_tokens", token)).await()
        SourcePlatform.redis.set(listOf("developers:ids:$id:access_token", token)).await()
        addRoleToDeveloper(id, DeveloperRole.ROLE_USER)
        return Developer(id, token)
    }

    suspend fun removeDeveloper(id: String) {
        val accessToken = getAccessToken(id)
        SourcePlatform.redis.srem(listOf("developers:ids", id)).await()
        SourcePlatform.redis.del(listOf("developers:access_tokens:$accessToken")).await()
        SourcePlatform.redis.srem(listOf("developers:access_tokens", accessToken)).await()
        SourcePlatform.redis.del(listOf("developers:ids:$id:access_token")).await()
        SourcePlatform.redis.del(listOf("developers:$id:roles")).await()
    }

    suspend fun getAccessToken(id: String): String {
        return SourcePlatform.redis.get("developers:ids:$id:access_token").await().toString(StandardCharsets.UTF_8)
    }

    suspend fun setAccessToken(id: String, accessToken: String) {
        //remove existing token
        val existingToken = SourcePlatform.redis.get("developers:ids:$id:access_token").await()
        if (existingToken != null) {
            val existingTokenStr = existingToken.toString(StandardCharsets.UTF_8)
            SourcePlatform.redis.srem(listOf("developers:access_tokens", existingTokenStr)).await()
            SourcePlatform.redis.del(listOf("developers:access_tokens:$existingToken")).await()
        } else {
            //add developer first
            SourcePlatform.redis.sadd(listOf("developers:ids", id)).await()
        }

        //set new token
        SourcePlatform.redis.set(listOf("developers:access_tokens:$accessToken", id)).await()
        SourcePlatform.redis.sadd(listOf("developers:access_tokens", accessToken)).await()
        SourcePlatform.redis.set(listOf("developers:ids:$id:access_token", accessToken)).await()
    }

    suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        return SourcePlatform.redis.smembers("developers:$developerId:roles").await()
            .map { DeveloperRole.fromString(it.toString(StandardCharsets.UTF_8)) }
    }

    suspend fun getDeveloperAccessPermissions(developerId: String): List<AccessPermission> {
        return getDeveloperRoles(developerId).flatMap { getRoleAccessPermissions(it) }
    }

    suspend fun hasInstrumentAccess(developerId: String, locationPattern: String): Boolean {
        val permissions = getDeveloperAccessPermissions(developerId)
        if (permissions.isEmpty()) {
            if (log.isTraceEnabled) log.trace("Developer {} has full access", developerId)
            return true
        } else {
            if (log.isTraceEnabled) log.trace("Developer {} has permissions {}", developerId, permissions)
        }

        val devBlackLists = permissions.filter { it.type == AccessType.BLACK_LIST }
        val inBlackList = devBlackLists.any { it ->
            val patterns = it.locationPatterns.map {
                it.replace(".", "\\.").replace("*", ".+")
            }
            patterns.any { locationPattern.matches(Regex(it)) }
        }

        val devWhiteLists = permissions.filter { it.type == AccessType.WHITE_LIST }
        val inWhiteList = devWhiteLists.any { it ->
            val patterns = it.locationPatterns.map {
                it.replace(".", "\\.").replace("*", ".+")
            }
            patterns.any { locationPattern.matches(Regex(it)) }
        }

        return if (devWhiteLists.isEmpty()) {
            !inBlackList
        } else if (devBlackLists.isEmpty()) {
            inWhiteList
        } else {
            !inBlackList || inWhiteList
        }
    }

    suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        val accessPermissions = SourcePlatform.redis.smembers("roles:${role.roleName}:access_permissions").await()
        return accessPermissions.map { getAccessPermission(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun getAccessPermissions(): Set<AccessPermission> {
        val accessPermissions = SourcePlatform.redis.smembers("access_permissions").await()
        return accessPermissions.map { getAccessPermission(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun hasAccessPermission(id: String): Boolean {
        return SourcePlatform.redis.sismember("access_permissions", id).await().toBoolean()
    }

    suspend fun getAccessPermission(id: String): AccessPermission {
        val accessPermissions = SourcePlatform.redis.get("access_permissions:$id").await()
        val dataObject = JsonObject(accessPermissions.toString(StandardCharsets.UTF_8))
        return AccessPermission(
            id,
            dataObject.getJsonArray("locationPatterns").map { it.toString() },
            AccessType.valueOf(dataObject.getString("type"))
        )
    }

    suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        SourcePlatform.redis.sadd(listOf("access_permissions", id)).await()
        SourcePlatform.redis.set(
            listOf(
                "access_permissions:$id",
                JsonObject()
                    .put("locationPatterns", locationPatterns)
                    .put("type", type.name)
                    .toString()
            )
        ).await()
    }

    suspend fun removeAccessPermission(id: String) {
        getRoles().forEach {
            removeAccessPermissionFromRole(id, it)
        }
        SourcePlatform.redis.srem(listOf("access_permissions", id)).await()
        SourcePlatform.redis.del(listOf("access_permissions:$id")).await()
    }

    suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        SourcePlatform.redis.sadd(listOf("roles:${role.roleName}:access_permissions", id)).await()
    }

    suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        SourcePlatform.redis.srem(listOf("roles:${role.roleName}:access_permissions", id)).await()
    }

    suspend fun getDeveloperDataRedactions(developerId: String): List<DataRedaction> {
        return getDeveloperRoles(developerId).flatMap { getRoleDataRedactions(it) }
    }

    suspend fun getDataRedactions(): Set<DataRedaction> {
        val roles = SourcePlatform.redis.smembers("data_redactions").await()
        return roles.map { getDataRedaction(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun hasDataRedaction(id: String): Boolean {
        return SourcePlatform.redis.sismember("data_redactions", id).await().toBoolean()
    }

    suspend fun getDataRedaction(id: String): DataRedaction {
        val permission = SourcePlatform.redis.get("data_redactions:$id").await()
        return DataRedaction(
            id,
            permission.toString(StandardCharsets.UTF_8)
        )
    }

    suspend fun addDataRedaction(id: String, redactionPattern: String) {
        SourcePlatform.redis.sadd(listOf("data_redactions", id)).await()
        SourcePlatform.redis.set(listOf("data_redactions:$id", redactionPattern)).await()
    }

    suspend fun removeDataRedaction(id: String) {
        getRoles().forEach {
            removeDataRedactionFromRole(id, it)
        }
        SourcePlatform.redis.srem(listOf("data_redactions", id)).await()
        SourcePlatform.redis.del(listOf("data_redactions:$id")).await()
    }

    suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        SourcePlatform.redis.sadd(listOf("roles:${role.roleName}:data_redactions", id)).await()
    }

    suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        SourcePlatform.redis.srem(listOf("roles:${role.roleName}:data_redactions", id)).await()
    }

    suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        val dataRedactions = SourcePlatform.redis.smembers("roles:${role.roleName}:data_redactions").await()
        return dataRedactions.map { getDataRedaction(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun getRoles(): Set<DeveloperRole> {
        val roles = SourcePlatform.redis.smembers("roles").await()
        return roles.map { DeveloperRole.fromString(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        SourcePlatform.redis.sadd(listOf("developers:$id:roles", role.roleName)).await()
    }

    suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        SourcePlatform.redis.srem(listOf("developers:$id:roles", role.roleName)).await()
    }

    suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        SourcePlatform.redis.sadd(listOf("roles", role.roleName)).await()
        SourcePlatform.redis.sadd(listOf("roles:${role.roleName}:permissions", permission.name)).await()
    }

    suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        SourcePlatform.redis.srem(listOf("roles:${role.roleName}:permissions", permission.name)).await()
    }

    suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val permissions = SourcePlatform.redis.smembers("roles:${role.roleName}:permissions").await()
        return permissions.map { RolePermission.valueOf(it.toString(StandardCharsets.UTF_8)) }.toSet()
    }

    suspend fun getDeveloperPermissions(id: String): Set<RolePermission> {
        return getDeveloperRoles(id).flatMap { getRolePermissions(it) }.toSet()
    }

    suspend fun hasPermission(id: String, permission: RolePermission): Boolean {
        log.trace("Checking permission: $permission - Id: $id")
        return getDeveloperRoles(id).any { getRolePermissions(it).contains(permission) }
    }
}
