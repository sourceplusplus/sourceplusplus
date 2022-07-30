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
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.platform.storage.MemoryStorage
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType

@ExtendWith(VertxExtension::class)
class MemoryStorageITTest {

    @BeforeEach
    fun setupInit(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)
        SourceStorage.setup(
            storage,
            JsonObject().put(
                "spp-platform",
                JsonObject()
                    .put("jwt", JsonObject())
                    .put("pii-redaction", JsonObject().put("enabled", "false"))
            )
        )
        SourceStorage.reset()
    }

    @Test
    fun getDevelopers(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        assertEquals(1, storage.getDevelopers().size)
        storage.addDeveloper("dev1", "token")
        assertEquals(2, storage.getDevelopers().size)

        assertNotNull(storage.getDevelopers().find { it.id == "dev1" })
    }

    @Test
    fun getDeveloperByAccessToken(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        assertEquals(1, storage.getDevelopers().size)
        storage.addDeveloper("dev1", "token")
        assertEquals(2, storage.getDevelopers().size)

        val developer = storage.getDeveloperByAccessToken("token")
        assertEquals("dev1", developer?.id)
    }

    @Test
    fun removeDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        val id = "dev_3"
        val token = "token_3"
        storage.addDeveloper(id, token)
        val developer = storage.getDeveloperByAccessToken(token)
        assertNotNull(developer)
        assertEquals(id, developer?.id)

        storage.removeDeveloper(id)
        val updatedDeveloper = storage.getDeveloperByAccessToken(token)
        assertNull(updatedDeveloper)
    }

    @Test
    fun setAccessToken(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        val id = "dev_4"
        val token = "token_4"
        storage.addDeveloper(id, token)
        val developer = storage.getDeveloperByAccessToken(token)
        assertEquals(id, developer?.id)

        storage.setAccessToken(id, "newToken")
        assertNull(storage.getDeveloperByAccessToken(token))

        val developerWithNewToken = storage.getDeveloperByAccessToken("newToken")
        assertEquals(id, developerWithNewToken?.id)
    }

    @Test
    fun hasRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        val developerRole = DeveloperRole.fromString("test_role")
        assertFalse(storage.hasRole(developerRole))

        storage.addRole(developerRole)
        assertTrue(storage.hasRole(developerRole))
    }

    @Test
    fun addRemoveRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        val developerRole = DeveloperRole.fromString("test_role_2")
        assertFalse(storage.hasRole(developerRole))

        storage.addRole(developerRole)
        assertTrue(storage.hasRole(developerRole))

        storage.removeRole(developerRole)
        assertFalse(storage.hasRole(developerRole))
    }

    @Test
    fun getDeveloperRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        val id = "dev_5"
        storage.addDeveloper(id, "token_5")
        val developerRole = DeveloperRole.fromString("dev_role")
        storage.addRole(developerRole)

        val developerRoles = storage.getDeveloperRoles(id)
        assertEquals(0, developerRoles.size)

        storage.addRoleToDeveloper(id, developerRole)
        val updatedDeveloperRoles = storage.getDeveloperRoles(id)
        assertEquals(1, updatedDeveloperRoles.size)
        assertEquals("dev_role", updatedDeveloperRoles[0].roleName)
    }

    @Test
    fun updateDataRedactionInRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = MemoryStorage(vertx)

        storage.addDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value1")
        val developerRole = DeveloperRole.fromString("test_role")
        storage.addRole(developerRole)
        storage.addDataRedactionToRole("test", developerRole)
        val dataRedactions = storage.getRoleDataRedactions(developerRole)
        assertEquals(1, dataRedactions.size)
        assertEquals("value1", dataRedactions.toList()[0].replacement)

        storage.updateDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value2")
        val updatedDataRedactions = storage.getRoleDataRedactions(developerRole)
        assertEquals(1, updatedDataRedactions.size)
        assertEquals("value2", updatedDataRedactions.toList()[0].replacement)
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
}
