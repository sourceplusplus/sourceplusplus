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
package integration.storage

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.platform.core.storage.RedisStorage
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType

@ExtendWith(VertxExtension::class)
class RedisStorageTest {

    @Test
    fun updateDataRedactionInRole(vertx: Vertx, context: VertxTestContext): Unit = runBlocking(vertx.dispatcher()) {
        val storage = RedisStorage()
        storage.init(vertx, JsonObject().put("host", "localhost").put("port", 6379))

        storage.addDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value1")
        storage.addRole("test_role")
        storage.addDataRedactionToRole("test", DeveloperRole.fromString("test_role"))
        val dataRedactions = storage.getRoleDataRedactions(DeveloperRole.fromString("test_role"))
        Assertions.assertEquals(1, dataRedactions.size)
        Assertions.assertEquals("value1", dataRedactions.toList()[0].replacement)

        storage.updateDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value2")
        val updatedDataRedactions = storage.getRoleDataRedactions(DeveloperRole.fromString("test_role"))
        Assertions.assertEquals(1, updatedDataRedactions.size)
        Assertions.assertEquals("value2", updatedDataRedactions.toList()[0].replacement)

        context.completeNow()
    }
}
