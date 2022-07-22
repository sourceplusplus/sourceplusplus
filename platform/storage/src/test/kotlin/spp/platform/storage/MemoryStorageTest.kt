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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType

class MemoryStorageTest {

    @Test
    fun updateDataRedactionInRole(): Unit = runBlocking {
        val vertx = Vertx.vertx()
        val storage = MemoryStorage(vertx)

        storage.addDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value1")
        storage.addRole(DeveloperRole.fromString("test_role"))
        storage.addDataRedactionToRole("test", DeveloperRole.fromString("test_role"))
        val dataRedactions = storage.getRoleDataRedactions(DeveloperRole.fromString("test_role"))
        assertEquals(1, dataRedactions.size)
        assertEquals("value1", dataRedactions.toList()[0].replacement)

        storage.updateDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value2")
        val updatedDataRedactions = storage.getRoleDataRedactions(DeveloperRole.fromString("test_role"))
        assertEquals(1, updatedDataRedactions.size)
        assertEquals("value2", updatedDataRedactions.toList()[0].replacement)

        vertx.close()
    }
}
