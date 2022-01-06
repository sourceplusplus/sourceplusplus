package spp.platform.core

import io.vertx.core.json.JsonObject
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.platform.core.storage.CoreStorage
import spp.protocol.auth.*
import spp.protocol.developer.Developer

object SourceStorage {

    private val log = LoggerFactory.getLogger(SourceStorage::class.java)

    lateinit var storage: CoreStorage
    lateinit var systemAccessToken: String

    suspend fun setup(storage: CoreStorage, config: JsonObject) {
        this.storage = storage

        //todo: if clustered, check if defaults are already set
        systemAccessToken = if (!System.getenv("SPP_SYSTEM_ACCESS_TOKEN").isNullOrBlank()) {
            System.getenv("SPP_SYSTEM_ACCESS_TOKEN")
        } else {
            val systemAccessToken = config.getJsonObject("spp-platform").getString("access_token")
            if (systemAccessToken != null) {
                systemAccessToken
            } else {
                log.warn("No system access token provided. Using default: {}", "change-me")
                "change-me"
            }
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
