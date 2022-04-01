package integration.storage

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import spp.platform.core.storage.RedisStorage
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType

class RedisStorageTest {

    @Test
    fun updateDataRedactionInRole(): Unit = runBlocking {
        val vertx = Vertx.vertx()
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

        vertx.close()
    }
}
