package integration

import graphql.Assert
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.platform.storage.CoreStorage
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.AccessType
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RedactionType

abstract class BaseStorageITTest<T : CoreStorage> {

    lateinit var storageInstance: T
    abstract suspend fun createInstance(vertx: Vertx): T

    @BeforeEach
    fun setupInit(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance = createInstance(vertx)
        SourceStorage.setup(
            storageInstance,
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
        Assertions.assertEquals(1, storageInstance.getDevelopers().size)
        storageInstance.addDeveloper("dev_1", "token")
        Assertions.assertEquals(2, storageInstance.getDevelopers().size)

        Assertions.assertNotNull(storageInstance.getDevelopers().find { it.id == "dev_1" })
    }

    @Test
    fun getDeveloperByAccessToken(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        Assertions.assertEquals(1, storageInstance.getDevelopers().size)
        storageInstance.addDeveloper("dev_2", "token")
        Assertions.assertEquals(2, storageInstance.getDevelopers().size)

        val developer = storageInstance.getDeveloperByAccessToken("token")
        Assertions.assertEquals("dev_2", developer?.id)
    }

    @Test
    fun hasDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addDeveloper("dev_6", "token_6")

        Assert.assertTrue(storageInstance.hasDeveloper("dev_6"))
    }

    @Test
    fun addDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev_5"
        val token = "token_5"
        storageInstance.addDeveloper(id, token)
        val developer = storageInstance.getDeveloperByAccessToken(token)
        Assertions.assertNotNull(developer)
        Assertions.assertEquals(id, developer?.id)

        Assertions.assertNotNull(storageInstance.getDevelopers().find { it.id == "dev_5" })
    }

    @Test
    fun removeDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev_3"
        val token = "token_3"
        storageInstance.addDeveloper(id, token)
        val developer = storageInstance.getDeveloperByAccessToken(token)
        Assertions.assertNotNull(developer)
        Assertions.assertEquals(id, developer?.id)

        storageInstance.removeDeveloper(id)
        val updatedDeveloper = storageInstance.getDeveloperByAccessToken(token)
        Assertions.assertNull(updatedDeveloper)
    }

    @Test
    fun setAccessToken(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev_4"
        val token = "token_4"
        storageInstance.addDeveloper(id, token)
        val developer = storageInstance.getDeveloperByAccessToken(token)
        Assertions.assertEquals(id, developer?.id)

        storageInstance.setAccessToken(id, "newToken")
        Assertions.assertNull(storageInstance.getDeveloperByAccessToken(token))

        val developerWithNewToken = storageInstance.getDeveloperByAccessToken("newToken")
        Assertions.assertEquals(id, developerWithNewToken?.id)
    }

    @Test
    fun hasRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role")
        Assertions.assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        Assertions.assertTrue(storageInstance.hasRole(developerRole))
    }

    @Test
    fun removeRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_2")
        Assertions.assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        Assertions.assertTrue(storageInstance.hasRole(developerRole))

        storageInstance.removeRole(developerRole)
        Assertions.assertFalse(storageInstance.hasRole(developerRole))
    }

    @Test
    fun addRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_3")
        Assertions.assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        Assertions.assertTrue(storageInstance.hasRole(developerRole))
    }

    @Test
    fun getDeveloperRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev_5"
        storageInstance.addDeveloper(id, "token_5")
        val developerRole = DeveloperRole.fromString("dev_role")
        storageInstance.addRole(developerRole)

        val developerRoles = storageInstance.getDeveloperRoles(id)
        Assertions.assertEquals(0, developerRoles.size)

        storageInstance.addRoleToDeveloper(id, developerRole)
        val updatedDeveloperRoles = storageInstance.getDeveloperRoles(id)
        Assertions.assertEquals(1, updatedDeveloperRoles.size)
        Assertions.assertEquals("dev_role", updatedDeveloperRoles[0].roleName)
    }

    @Test
    fun getRoleAccessPermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_4")
        storageInstance.addRole(developerRole)

        val id = "accessId"
        storageInstance.addAccessPermission(id, listOf("pattern"), AccessType.BLACK_LIST)
        val roleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        Assertions.assertEquals(0, roleAccessPermissions.size)
        Assertions.assertNull(roleAccessPermissions.find { it.id == id })

        storageInstance.addAccessPermissionToRole(id, developerRole)
        val updatedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        Assertions.assertEquals(1, updatedRoleAccessPermissions.size)
        Assertions.assertNotNull(updatedRoleAccessPermissions.find { it.id == id })


        storageInstance.addAccessPermission("accessId1", listOf("pattern"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermissionToRole("accessId1", developerRole)
        storageInstance.addAccessPermission("accessId2", listOf("pattern"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermissionToRole("accessId2", developerRole)
        val multipleRoleAccess = storageInstance.getRoleAccessPermissions(developerRole)
        Assertions.assertEquals(3, multipleRoleAccess.size)
        Assertions.assertNotNull(multipleRoleAccess.find { it.id == "accessId1" })
        Assertions.assertNotNull(multipleRoleAccess.find { it.id == "accessId2" })
    }

    @Test
    fun updateDataRedactionInRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value1")
        val developerRole = DeveloperRole.fromString("test_role")
        storageInstance.addRole(developerRole)
        storageInstance.addDataRedactionToRole("test", developerRole)
        val dataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        Assertions.assertEquals(1, dataRedactions.size)
        Assertions.assertEquals("value1", dataRedactions.toList()[0].replacement)

        storageInstance.updateDataRedaction("test", RedactionType.IDENTIFIER_MATCH, "lookup", "value2")
        val updatedDataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        Assertions.assertEquals(1, updatedDataRedactions.size)
        Assertions.assertEquals("value2", updatedDataRedactions.toList()[0].replacement)
    }

    @Test
    fun reset(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        SourceStorage.addRole(DeveloperRole.fromString("resetRole"))
        Assertions.assertTrue(SourceStorage.getRoles().contains(DeveloperRole.fromString("resetRole")))
        SourceStorage.addDeveloper("resetDeveloper")
        Assertions.assertNotNull(SourceStorage.getDevelopers().find { it.id == "resetDeveloper" })
        SourceStorage.addDataRedaction(
            "resetDataRedaction",
            RedactionType.IDENTIFIER_MATCH,
            "resetDataRedaction",
            "resetDataRedaction"
        )
        Assertions.assertNotNull(SourceStorage.getDataRedactions().find { it.id == "resetDataRedaction" })

        SourceStorage.reset()

        Assertions.assertFalse(SourceStorage.getRoles().contains(DeveloperRole.fromString("resetRole")))
        Assertions.assertNull(SourceStorage.getDevelopers().find { it.id == "resetDeveloper" })
        Assertions.assertNull(SourceStorage.getDataRedactions().find { it.id == "resetDataRedaction" })
    }
}