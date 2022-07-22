package integration.storage

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.platform.storage.RedisStorage
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType

@ExtendWith(VertxExtension::class)
class SourceStorageTest {

    @Test
    fun reset(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val storage = RedisStorage()
        storage.init(vertx, JsonObject().put("host", "localhost").put("port", 6379))
        SourceStorage.setup(
            storage,
            JsonObject().put(
                "spp-platform",
                JsonObject()
                    .put("jwt", JsonObject())
                    .put("pii-redaction", JsonObject().put("enabled", "false"))
            )
        )

        SourceStorage.addRole("resetRole")
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
