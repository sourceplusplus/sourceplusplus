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
package spp.platform.storage

import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.Counter
import io.vertx.core.shareddata.Lock
import spp.platform.common.util.SecureUUID
import spp.protocol.instrument.LiveInstrument
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer

interface CoreStorage {

    suspend fun init(config: JsonObject) = Unit
    suspend fun counter(name: String): Counter
    suspend fun lock(name: String): Lock
    suspend fun lock(name: String, timeout: Long): Lock
    suspend fun <K, V> map(name: String): AsyncMap<K, V>
    suspend fun <T> get(name: String): T?
    suspend fun <T> put(name: String, value: T)

    suspend fun getClientAccessors(): List<ClientAccess>
    suspend fun getClientAccess(id: String): ClientAccess?
    suspend fun addClientAccess(id: String? = null, secret: String? = null): ClientAccess
    suspend fun removeClientAccess(id: String): Boolean
    suspend fun refreshClientAccess(id: String): ClientAccess

    suspend fun getDevelopers(): List<Developer>
    suspend fun getDeveloperByAuthorizationCode(code: String): Developer?
    suspend fun hasRole(role: DeveloperRole): Boolean
    suspend fun removeRole(role: DeveloperRole): Boolean
    suspend fun addRole(role: DeveloperRole): Boolean
    suspend fun hasDeveloper(id: String): Boolean
    suspend fun addDeveloper(id: String, authorizationCode: String): Developer
    suspend fun removeDeveloper(id: String)
    suspend fun setAuthorizationCode(id: String, code: String)
    suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole>
    suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission>
    suspend fun getAccessPermissions(): Set<AccessPermission>
    suspend fun hasAccessPermission(id: String): Boolean
    suspend fun getAccessPermission(id: String): AccessPermission
    suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType)
    suspend fun removeAccessPermission(id: String)
    suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole)
    suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole)
    suspend fun getDataRedactions(): Set<DataRedaction>
    suspend fun hasDataRedaction(id: String): Boolean
    suspend fun getDataRedaction(id: String): DataRedaction
    suspend fun addDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String)
    suspend fun updateDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String)
    suspend fun removeDataRedaction(id: String)
    suspend fun addDataRedactionToRole(id: String, role: DeveloperRole)
    suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole)
    suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction>
    suspend fun getRoles(): Set<DeveloperRole>
    suspend fun addRoleToDeveloper(id: String, role: DeveloperRole)
    suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole)
    suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission)
    suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission)
    suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission>

    /**
     * Add a new [LiveInstrument] to the platform.
     */
    suspend fun addLiveInstrument(instrument: LiveInstrument): LiveInstrument

    /**
     * Update an existing [LiveInstrument] on the platform with the given id.
     */
    suspend fun updateLiveInstrument(id: String, instrument: LiveInstrument): LiveInstrument

    /**
     * Remove the [LiveInstrument] with the given id.
     */
    suspend fun removeLiveInstrument(id: String): Boolean

    /**
     * Get the [LiveInstrument] with the given id.
     *
     * @param id The id of the [LiveInstrument] to get.
     * @param includeArchive Whether to include archived instruments.
     */
    suspend fun getLiveInstrument(id: String, includeArchive: Boolean = false): LiveInstrument?

    /**
     * Retrieve all [LiveInstrument]s.
     * @param includeArchive Whether to include archived instruments.
     */
    suspend fun getLiveInstruments(includeArchive: Boolean = false): List<LiveInstrument>

    /**
     * Get the archived [LiveInstrument] with the given id.
     *
     * @param id The id of the archived [LiveInstrument] to get.
     */
    suspend fun getArchiveLiveInstrument(id: String): LiveInstrument?

    /**
     * Retrieve all archived [LiveInstrument]s.
     */
    suspend fun getArchivedLiveInstruments(): List<LiveInstrument>

    suspend fun namespace(location: String): String = location

    fun generateClientAccess(id: String? = null, secret: String? = null): ClientAccess {
        if (id?.isBlank() == true || secret?.isBlank() == true) {
            throw IllegalArgumentException("id and secret must be non-blank")
        }
        return ClientAccess(id ?: generateClientId(), secret ?: generateClientSecret())
    }

    fun generateClientId(): String {
        return "spp_ci_" + SecureUUID.get()
    }

    fun generateClientSecret(): String {
        return "spp_cs_" + SecureUUID.get()
    }
}
