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
    fun getRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_4")
        Assertions.assertFalse(storageInstance.getRoles().contains(developerRole))
        storageInstance.addRole(developerRole)
        Assertions.assertTrue(storageInstance.getRoles().contains(developerRole))
    }

    @Test
    fun addGetDeveloperRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
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
    fun removeRoleFromDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "dev6"
        storageInstance.addDeveloper(id, "token6")
        val developerRole = DeveloperRole.fromString("devRole")
        storageInstance.addRole(developerRole)
        storageInstance.addRoleToDeveloper(id, developerRole)

        Assertions.assertTrue(storageInstance.getDeveloperRoles(id).contains(developerRole))

        storageInstance.removeRoleFromDeveloper(id, developerRole)
        Assertions.assertFalse(storageInstance.getDeveloperRoles(id).contains(developerRole))
    }

    @Test
    fun getRoleAccessPermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_6")
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

        val id1 = "accessId1"
        val id2 = "accessId2"
        storageInstance.addAccessPermission(id1, listOf("pattern"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermissionToRole(id1, developerRole)
        storageInstance.addAccessPermission(id2, listOf("pattern"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermissionToRole(id2, developerRole)
        val multipleRoleAccess = storageInstance.getRoleAccessPermissions(developerRole)
        Assertions.assertEquals(3, multipleRoleAccess.size)
        Assertions.assertNotNull(multipleRoleAccess.find { it.id == id1 })
        Assertions.assertNotNull(multipleRoleAccess.find { it.id == id2 })
    }

    @Test
    fun getAccessPermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance.addAccessPermission("accessId3", listOf("pattern3"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermission("accessId4", listOf("pattern4"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermission("accessId5", listOf("pattern5"), AccessType.BLACK_LIST)
        val accessPermissions = storageInstance.getAccessPermissions()
        Assertions.assertEquals(3, accessPermissions.size)

    }

    @Test
    fun hasAccessPermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "accessId6"
        Assertions.assertFalse(storageInstance.hasAccessPermission(id))
        storageInstance.addAccessPermission(id, listOf("pattern6"), AccessType.WHITE_LIST)
        Assertions.assertTrue(storageInstance.hasAccessPermission(id))

    }

    @Test
    fun getAccessPermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "accessId7"
        storageInstance.addAccessPermission(id, listOf("pattern7"), AccessType.WHITE_LIST)
        val accessPermission = storageInstance.getAccessPermission(id)
        Assertions.assertEquals(id, accessPermission.id)
        Assertions.assertEquals(1, accessPermission.locationPatterns.size)
        Assertions.assertEquals("pattern7", accessPermission.locationPatterns[0])
    }

    @Test
    fun addRemovePermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "accessId8"
        Assertions.assertFalse(storageInstance.hasAccessPermission(id))
        storageInstance.addAccessPermission(id, listOf("pattern8"), AccessType.WHITE_LIST)
        Assertions.assertTrue(storageInstance.hasAccessPermission(id))
        storageInstance.removeAccessPermission(id)
        Assertions.assertFalse(storageInstance.hasAccessPermission(id))
    }

    @Test
    fun addRemoveAccessPermissionToRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString("test_role_7")
        storageInstance.addRole(developerRole)

        val id = "accessId9"
        storageInstance.addAccessPermission(id, listOf("pattern9"), AccessType.BLACK_LIST)
        storageInstance.addAccessPermissionToRole(id, developerRole)

        val updatedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        Assertions.assertEquals(1, updatedRoleAccessPermissions.size)

        storageInstance.removeAccessPermissionFromRole(id, developerRole)
        val removedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        Assertions.assertEquals(0, removedRoleAccessPermissions.size)
    }

    @Test
    fun addDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction1"
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup1", "value1")
        val dataRedaction = storageInstance.getDataRedaction(id)
        Assertions.assertEquals(id, dataRedaction.id)
        Assertions.assertEquals(RedactionType.IDENTIFIER_MATCH, dataRedaction.type)
        Assertions.assertEquals("lookup1", dataRedaction.lookup)
        Assertions.assertEquals("value1", dataRedaction.replacement)
    }

    @Test
    fun hasDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction2"
        Assertions.assertFalse(storageInstance.hasDataRedaction(id))
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup2", "value2")
        Assertions.assertTrue(storageInstance.hasDataRedaction(id))
    }

    @Test
    fun getDataRedactions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id3 = "redaction3"
        val id4 = "redaction4"
        val id5 = "redaction5"
        Assertions.assertEquals(0, storageInstance.getDataRedactions().size)
        storageInstance.addDataRedaction(id3, RedactionType.IDENTIFIER_MATCH, "lookup3", "value3")
        storageInstance.addDataRedaction(id4, RedactionType.VALUE_REGEX, "lookup4", "value4")
        storageInstance.addDataRedaction(id5, RedactionType.IDENTIFIER_MATCH, "lookup5", "value5")
        Assertions.assertEquals(3, storageInstance.getDataRedactions().size)
    }

    @Test
    fun updateDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction6"
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup1", "value1")
        val dataRedaction = storageInstance.getDataRedaction(id)
        Assertions.assertEquals(id, dataRedaction.id)
        Assertions.assertEquals(RedactionType.IDENTIFIER_MATCH, dataRedaction.type)
        Assertions.assertEquals("lookup1", dataRedaction.lookup)
        Assertions.assertEquals("value1", dataRedaction.replacement)

        storageInstance.updateDataRedaction(id, RedactionType.VALUE_REGEX, "lookup6", "value6")
        val updatedDataRedaction = storageInstance.getDataRedaction(id)
        Assertions.assertEquals(id, updatedDataRedaction.id)
        Assertions.assertEquals(RedactionType.VALUE_REGEX, updatedDataRedaction.type)
        Assertions.assertEquals("lookup6", updatedDataRedaction.lookup)
        Assertions.assertEquals("value6", updatedDataRedaction.replacement)
    }

    @Test
    fun removeDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = "redaction7"
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup7", "value7")
        Assertions.assertTrue(storageInstance.hasDataRedaction(id))
        storageInstance.removeDataRedaction(id)
        Assertions.assertFalse(storageInstance.hasDataRedaction(id))
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