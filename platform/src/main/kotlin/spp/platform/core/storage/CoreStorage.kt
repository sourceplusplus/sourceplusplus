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
package spp.platform.core.storage

import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer

interface CoreStorage {

    suspend fun getDevelopers(): List<Developer>
    suspend fun getDeveloperByAccessToken(token: String): Developer?
    suspend fun hasRole(roleName: String): Boolean
    suspend fun removeRole(role: DeveloperRole): Boolean
    suspend fun addRole(roleName: String): Boolean
    suspend fun hasDeveloper(id: String): Boolean
    suspend fun addDeveloper(id: String, token: String): Developer
    suspend fun removeDeveloper(id: String)
    suspend fun setAccessToken(id: String, accessToken: String)
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
    suspend fun addDataRedaction(id: String, redactionPattern: String)
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
}
