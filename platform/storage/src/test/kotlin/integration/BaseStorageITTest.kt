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
package integration

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.platform.storage.CoreStorage
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.*

@ExtendWith(VertxExtension::class)
abstract class BaseStorageITTest<T : CoreStorage> {

    companion object {
        val baseConfig: JsonObject = JsonObject()
            .put(
                "spp-platform",
                JsonObject()
                    .put("jwt", JsonObject())
                    .put("pii-redaction", JsonObject().put("enabled", "false"))
            )
            .put(
                "client-access",
                JsonObject()
                    .put("enabled", "true")
                    .put(
                        "accessors", JsonArray().add(
                            JsonObject()
                                .put("id", "test-id")
                                .put("secret", "test-secret")
                        )
                    )
            )
    }

    lateinit var storageInstance: T
    abstract suspend fun createInstance(vertx: Vertx): T

    @BeforeEach
    fun setupInit(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance = createInstance(vertx)
        SourceStorage.setup(storageInstance)
        SourceStorage.reset()
    }

    @Test
    fun reset(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        SourceStorage.addRole(DeveloperRole.fromString("resetRole"))
        assertTrue(SourceStorage.getRoles().contains(DeveloperRole.fromString("resetRole")))
        SourceStorage.addDeveloper("resetDeveloper")
        assertNotNull(SourceStorage.getDevelopers().find { it.id == "resetDeveloper" })
        SourceStorage.addDataRedaction(
            "resetDataRedaction",
            RedactionType.IDENTIFIER_MATCH,
            "resetDataRedaction",
            "resetDataRedaction"
        )
        assertNotNull(SourceStorage.getDataRedactions().find { it.id == "resetDataRedaction" })

        SourceStorage.reset()

        assertFalse(SourceStorage.getRoles().contains(DeveloperRole.fromString("resetRole")))
        assertNull(SourceStorage.getDevelopers().find { it.id == "resetDeveloper" })
        assertNull(SourceStorage.getDataRedactions().find { it.id == "resetDataRedaction" })
    }

    @Test
    fun getDevelopers(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(1, storageInstance.getDevelopers().size)
        storageInstance.addDeveloper("dev_1", "token")
        assertEquals(2, storageInstance.getDevelopers().size)

        assertNotNull(storageInstance.getDevelopers().find { it.id == "dev_1" })
    }

    @Test
    fun getDeveloperByAccessToken(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(1, storageInstance.getDevelopers().size)
        storageInstance.addDeveloper("dev_2", "token")
        assertEquals(2, storageInstance.getDevelopers().size)

        val developer = storageInstance.getDeveloperByAccessToken("token")
        assertEquals("dev_2", developer?.id)
    }

    @Test
    fun hasDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(listOf("system"), storageInstance.getDevelopers().map { it.id })
        storageInstance.addDeveloper("dev_6", "token_6")

        assertTrue(storageInstance.hasDeveloper("dev_6"))
        assertEquals(2, storageInstance.getDevelopers().size)
    }

    @Test
    fun addDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(listOf("system"), storageInstance.getDevelopers().map { it.id })
        val id = "dev_5"
        val token = "token_5"
        storageInstance.addDeveloper(id, token)
        val developer = storageInstance.getDeveloperByAccessToken(token)
        assertNotNull(developer)
        assertEquals(id, developer?.id)

        assertNotNull(storageInstance.getDevelopers().find { it.id == "dev_5" })
        assertEquals(2, storageInstance.getDevelopers().size)
    }

    @Test
    fun removeDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(1, storageInstance.getDevelopers().size)
        val id = "dev_3"
        val token = "token_3"
        storageInstance.addDeveloper(id, token)
        assertEquals(2, storageInstance.getDevelopers().size)
        val developer = storageInstance.getDeveloperByAccessToken(token)
        assertNotNull(developer)
        assertEquals(id, developer?.id)

        storageInstance.removeDeveloper(id)
        val updatedDeveloper = storageInstance.getDeveloperByAccessToken(token)
        assertNull(updatedDeveloper)
        assertEquals(1, storageInstance.getDevelopers().size)
    }

    @Test
    fun setAccessToken(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(listOf("system"), storageInstance.getDevelopers().map { it.id })
        val id = "dev_4"
        val token = "token_4"
        storageInstance.addDeveloper(id, token)
        val developer = storageInstance.getDeveloperByAccessToken(token)
        assertEquals(id, developer?.id)

        storageInstance.setAccessToken(id, "newToken")
        assertNull(storageInstance.getDeveloperByAccessToken(token))

        val developerWithNewToken = storageInstance.getDeveloperByAccessToken("newToken")
        assertEquals(id, developerWithNewToken?.id)
    }

    @Test
    fun hasRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role")
        assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.hasRole(developerRole))
    }

    @Test
    fun removeRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_2")
        assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.hasRole(developerRole))

        storageInstance.removeRole(developerRole)
        assertFalse(storageInstance.hasRole(developerRole))
    }

    @Test
    fun addRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_3")
        assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.hasRole(developerRole))
    }

    @Test
    fun getRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_4")
        assertFalse(storageInstance.getRoles().contains(developerRole))
        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.getRoles().contains(developerRole))
    }

    @Test
    fun addGetDeveloperRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev_5"
        storageInstance.addDeveloper(id, "token_5")
        val developerRole = DeveloperRole.fromString("dev_role")
        storageInstance.addRole(developerRole)

        val developerRoles = storageInstance.getDeveloperRoles(id)
        assertEquals(0, developerRoles.size)

        storageInstance.addRoleToDeveloper(id, developerRole)
        val updatedDeveloperRoles = storageInstance.getDeveloperRoles(id)
        assertEquals(1, updatedDeveloperRoles.size)
        assertEquals("dev_role", updatedDeveloperRoles[0].roleName)
    }

    @Test
    fun removeRoleFromDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev6"
        storageInstance.addDeveloper(id, "token6")
        val developerRole = DeveloperRole.fromString("devRole")
        storageInstance.addRole(developerRole)
        storageInstance.addRoleToDeveloper(id, developerRole)

        assertTrue(storageInstance.getDeveloperRoles(id).contains(developerRole))

        storageInstance.removeRoleFromDeveloper(id, developerRole)
        assertFalse(storageInstance.getDeveloperRoles(id).contains(developerRole))
    }

    @Test
    fun addGetRolePermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("devRole11")
        storageInstance.addRole(developerRole)
        storageInstance.addPermissionToRole(developerRole, RolePermission.ADD_ROLE_PERMISSION)
        storageInstance.addPermissionToRole(developerRole, RolePermission.ADD_DEVELOPER)
        storageInstance.addPermissionToRole(developerRole, RolePermission.REMOVE_ROLE_PERMISSION)
        val rolePermissions = storageInstance.getRolePermissions(developerRole)
        assertEquals(3, rolePermissions.size)
        assertNotNull(rolePermissions.find { it.commandType == CommandType.LIVE_SERVICE })
    }

    @Test
    fun removePermissionFromRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("devRole12")
        storageInstance.addRole(developerRole)
        storageInstance.addPermissionToRole(developerRole, RolePermission.ADD_DEVELOPER_ROLE)
        val rolePermissions = storageInstance.getRolePermissions(developerRole)
        assertEquals(1, rolePermissions.size)
        assertNotNull(rolePermissions.find { it.commandType == CommandType.LIVE_SERVICE })

        storageInstance.removePermissionFromRole(developerRole, RolePermission.ADD_DEVELOPER_ROLE)
        val updatedRolePermissions = storageInstance.getRolePermissions(developerRole)
        assertEquals(0, updatedRolePermissions.size)
        assertNull(updatedRolePermissions.find { it.commandType == CommandType.LIVE_SERVICE })
    }

    @Test
    fun getRoleAccessPermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_6")
        storageInstance.addRole(developerRole)

        val id = "accessId"
        storageInstance.addAccessPermission(id, listOf("pattern"), AccessType.BLACK_LIST)
        val roleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        assertEquals(0, roleAccessPermissions.size)
        assertNull(roleAccessPermissions.find { it.id == id })

        storageInstance.addAccessPermissionToRole(id, developerRole)
        val updatedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        assertEquals(1, updatedRoleAccessPermissions.size)
        assertNotNull(updatedRoleAccessPermissions.find { it.id == id })

        val id1 = "accessId1"
        val id2 = "accessId2"
        storageInstance.addAccessPermission(id1, listOf("pattern"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermissionToRole(id1, developerRole)
        storageInstance.addAccessPermission(id2, listOf("pattern"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermissionToRole(id2, developerRole)
        val multipleRoleAccess = storageInstance.getRoleAccessPermissions(developerRole)
        assertEquals(3, multipleRoleAccess.size)
        assertNotNull(multipleRoleAccess.find { it.id == id1 })
        assertNotNull(multipleRoleAccess.find { it.id == id2 })
    }

    @Test
    fun getAccessPermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addAccessPermission("accessId3", listOf("pattern3"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermission("accessId4", listOf("pattern4"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermission("accessId5", listOf("pattern5"), AccessType.BLACK_LIST)
        val accessPermissions = storageInstance.getAccessPermissions()
        assertEquals(3, accessPermissions.size)
    }

    @Test
    fun hasAccessPermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "accessId6"
        assertFalse(storageInstance.hasAccessPermission(id))
        storageInstance.addAccessPermission(id, listOf("pattern6"), AccessType.WHITE_LIST)
        assertTrue(storageInstance.hasAccessPermission(id))
    }

    @Test
    fun getAccessPermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "accessId7"
        storageInstance.addAccessPermission(id, listOf("pattern7"), AccessType.WHITE_LIST)
        val accessPermission = storageInstance.getAccessPermission(id)
        assertEquals(id, accessPermission.id)
        assertEquals(1, accessPermission.locationPatterns.size)
        assertEquals("pattern7", accessPermission.locationPatterns[0])
    }

    @Test
    fun addRemovePermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "accessId8"
        assertFalse(storageInstance.hasAccessPermission(id))
        storageInstance.addAccessPermission(id, listOf("pattern8"), AccessType.WHITE_LIST)
        assertTrue(storageInstance.hasAccessPermission(id))
        storageInstance.removeAccessPermission(id)
        assertFalse(storageInstance.hasAccessPermission(id))
    }

    @Test
    fun addRemoveAccessPermissionToRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_7")
        storageInstance.addRole(developerRole)

        val id = "accessId9"
        storageInstance.addAccessPermission(id, listOf("pattern9"), AccessType.BLACK_LIST)
        storageInstance.addAccessPermissionToRole(id, developerRole)

        val updatedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        assertEquals(1, updatedRoleAccessPermissions.size)

        storageInstance.removeAccessPermissionFromRole(id, developerRole)
        val removedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        assertEquals(0, removedRoleAccessPermissions.size)
    }

    @Test
    fun addDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction1"
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup1", "value1")
        val dataRedaction = storageInstance.getDataRedaction(id)
        assertEquals(id, dataRedaction.id)
        assertEquals(RedactionType.IDENTIFIER_MATCH, dataRedaction.type)
        assertEquals("lookup1", dataRedaction.lookup)
        assertEquals("value1", dataRedaction.replacement)
    }

    @Test
    fun hasDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction2"
        assertFalse(storageInstance.hasDataRedaction(id))
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup2", "value2")
        assertTrue(storageInstance.hasDataRedaction(id))
    }

    @Test
    fun getDataRedactions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id3 = "redaction3"
        val id4 = "redaction4"
        val id5 = "redaction5"
        assertEquals(0, storageInstance.getDataRedactions().size)
        storageInstance.addDataRedaction(id3, RedactionType.IDENTIFIER_MATCH, "lookup3", "value3")
        storageInstance.addDataRedaction(id4, RedactionType.VALUE_REGEX, "lookup4", "value4")
        storageInstance.addDataRedaction(id5, RedactionType.IDENTIFIER_MATCH, "lookup5", "value5")
        assertEquals(3, storageInstance.getDataRedactions().size)
    }

    @Test
    fun updateDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertEquals(0, storageInstance.getDataRedactions().size)
        val id = "redaction6"
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup1", "value1")
        val dataRedaction = storageInstance.getDataRedaction(id)
        assertEquals(id, dataRedaction.id)
        assertEquals(RedactionType.IDENTIFIER_MATCH, dataRedaction.type)
        assertEquals("lookup1", dataRedaction.lookup)
        assertEquals("value1", dataRedaction.replacement)

        storageInstance.updateDataRedaction(id, RedactionType.VALUE_REGEX, "lookup6", "value6")
        val updatedDataRedaction = storageInstance.getDataRedaction(id)
        assertEquals(id, updatedDataRedaction.id)
        assertEquals(RedactionType.VALUE_REGEX, updatedDataRedaction.type)
        assertEquals("lookup6", updatedDataRedaction.lookup)
        assertEquals("value6", updatedDataRedaction.replacement)
    }

    @Test
    fun removeDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction7"
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup7", "value7")
        assertTrue(storageInstance.hasDataRedaction(id))
        storageInstance.removeDataRedaction(id)
        assertFalse(storageInstance.hasDataRedaction(id))
    }

    @Test
    fun removeDataRedactionFromRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addDataRedaction("redact8", RedactionType.IDENTIFIER_MATCH, "lookup8", "value8")
        val developerRole = DeveloperRole.fromString("devRole8")
        storageInstance.addRole(developerRole)
        storageInstance.addDataRedactionToRole("redact8", developerRole)
        val dataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        assertEquals(1, dataRedactions.size)
        assertEquals("value8", dataRedactions.toList()[0].replacement)

        storageInstance.removeDataRedactionFromRole("redact8", developerRole)
        val updatedDataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        assertEquals(0, updatedDataRedactions.size)
    }

    @Test
    fun getRoleDataRedactions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addDataRedaction("redact9", RedactionType.IDENTIFIER_MATCH, "lookup9", "value9")
        storageInstance.addDataRedaction("redact10", RedactionType.VALUE_REGEX, "lookup10", "value10")
        val developerRole = DeveloperRole.fromString("devRole9")
        storageInstance.addRole(developerRole)
        storageInstance.addDataRedactionToRole("redact9", developerRole)
        storageInstance.addDataRedactionToRole("redact10", developerRole)
        val dataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        assertEquals(2, dataRedactions.size)
        assertNotNull(dataRedactions.find { it.replacement == "value10" })
        assertNotNull(dataRedactions.find { it.replacement == "value9" })
    }

    @Test
    fun updateDataRedactionInRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value1")
        val developerRole = DeveloperRole.fromString("test_role")
        storageInstance.addRole(developerRole)
        storageInstance.addDataRedactionToRole("test", developerRole)
        val dataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        assertEquals(1, dataRedactions.size)
        assertEquals("value1", dataRedactions.toList()[0].replacement)

        storageInstance.updateDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value2")
        val updatedDataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        assertEquals(1, updatedDataRedactions.size)
        assertEquals("value2", updatedDataRedactions.toList()[0].replacement)
    }

    @Test
    fun addGetClientAccess(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "clientId1"
        val secret = "clientSecret1"
        assertNull(storageInstance.getClientAccess(id))
        assertEquals(1, storageInstance.getClientAccessors().size)

        storageInstance.addClientAccess(id, secret)
        val clientAccess = storageInstance.getClientAccess(id)
        assertNotNull(clientAccess)
        assertEquals(2, storageInstance.getClientAccessors().size)
        assertEquals(secret, clientAccess?.secret)
    }

    @Test
    fun removeClientAccess(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "clientId2"
        storageInstance.addClientAccess(id)
        val clientAccess = storageInstance.getClientAccess(id)
        assertNotNull(clientAccess)
        assertEquals(2, storageInstance.getClientAccessors().size)

        assertTrue(storageInstance.removeClientAccess(id))
        assertNull(storageInstance.getClientAccess(id))
        assertEquals(1, storageInstance.getClientAccessors().size)
    }

    @Test
    fun updateClientAccess(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "clientId3"
        val secret = "clientSecret3"
        storageInstance.addClientAccess(id, secret)
        val clientAccess = storageInstance.getClientAccess(id)
        assertEquals(secret, clientAccess?.secret)

        storageInstance.updateClientAccess(id)
        val updatedClientAccess = storageInstance.getClientAccess(id)
        assertNotEquals(secret, updatedClientAccess?.secret)
    }
}
