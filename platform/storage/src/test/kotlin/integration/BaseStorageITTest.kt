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
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import spp.platform.storage.CoreStorage
import spp.platform.storage.SourceStorage
import spp.protocol.instrument.*
import spp.protocol.instrument.event.LiveInstrumentAdded
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.platform.auth.*
import java.time.Instant
import java.util.*

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

    var testName: String? = null
    val testNameAsInstrumentId: String
        get() {
            return "spp_" + testName!!.replace("-", "_").replace(" ", "_")
                .lowercase().substringBefore("(")
        }
    val testNameAsUniqueInstrumentId: String
        get() {
            return testNameAsInstrumentId + "_" + UUID.randomUUID().toString().replace("-", "")
        }

    @BeforeEach
    open fun setUp(testInfo: TestInfo) {
        testName = testInfo.displayName
    }

    lateinit var storageInstance: T
    abstract suspend fun createInstance(vertx: Vertx): T

    @BeforeEach
    fun setupInit(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        storageInstance = createInstance(vertx)
        SourceStorage.setup(storageInstance)
    }

    @Test
    fun getDevelopers(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val devId = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(devId, "code")
        assertTrue(storageInstance.getDevelopers().isNotEmpty())
        assertNotNull(storageInstance.getDevelopers().find { it.id == devId })
    }

    @Test
    fun getDeveloperByAuthorizationCode(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val devId = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(devId, "code")

        val developer = storageInstance.getDeveloperByAuthorizationCode("code")
        assertEquals(devId, developer?.id)
    }

    @Test
    fun hasDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val devId = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(devId, testNameAsUniqueInstrumentId)

        assertTrue(storageInstance.hasDeveloper(devId))
    }

    @Test
    fun addDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val code = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(id, code)
        val developer = storageInstance.getDeveloperByAuthorizationCode(code)
        assertNotNull(developer)
        assertEquals(id, developer?.id)

        assertNotNull(storageInstance.getDevelopers().find { it.id == id })
    }

    @Test
    fun removeDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val code = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(id, code)
        val developer = storageInstance.getDeveloperByAuthorizationCode(code)
        assertNotNull(developer)
        assertEquals(id, developer?.id)

        storageInstance.removeDeveloper(id)
        val updatedDeveloper = storageInstance.getDeveloperByAuthorizationCode(code)
        assertNull(updatedDeveloper)
    }

    @Test
    fun setAuthorizationCode(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val code = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(id, code)
        val developer = storageInstance.getDeveloperByAuthorizationCode(code)
        assertEquals(id, developer?.id)

        storageInstance.setAuthorizationCode(id, "newCode")
        assertNull(storageInstance.getDeveloperByAuthorizationCode(code))

        val developerWithNewCode = storageInstance.getDeveloperByAuthorizationCode("newCode")
        assertEquals(id, developerWithNewCode?.id)
    }

    @Test
    fun hasRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.hasRole(developerRole))
    }

    @Test
    fun removeRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.hasRole(developerRole))

        storageInstance.removeRole(developerRole)
        assertFalse(storageInstance.hasRole(developerRole))
    }

    @Test
    fun addRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        assertFalse(storageInstance.hasRole(developerRole))

        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.hasRole(developerRole))
    }

    @Test
    fun getRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        assertFalse(storageInstance.getRoles().contains(developerRole))
        storageInstance.addRole(developerRole)
        assertTrue(storageInstance.getRoles().contains(developerRole))
    }

    @Test
    fun addGetDeveloperRoles(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(id, testNameAsUniqueInstrumentId)
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        storageInstance.addRole(developerRole)

        val developerRoles = storageInstance.getDeveloperRoles(id)
        assertEquals(0, developerRoles.size)

        storageInstance.addRoleToDeveloper(id, developerRole)
        val updatedDeveloperRoles = storageInstance.getDeveloperRoles(id)
        assertEquals(1, updatedDeveloperRoles.size)
        assertEquals(developerRole.roleName, updatedDeveloperRoles[0].roleName)
    }

    @Test
    fun removeRoleFromDeveloper(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        storageInstance.addDeveloper(id, "code_6")
        val developerRole = DeveloperRole.fromString("devRole")
        storageInstance.addRole(developerRole)
        storageInstance.addRoleToDeveloper(id, developerRole)

        assertTrue(storageInstance.getDeveloperRoles(id).contains(developerRole))

        storageInstance.removeRoleFromDeveloper(id, developerRole)
        assertFalse(storageInstance.getDeveloperRoles(id).contains(developerRole))
    }

    @Test
    fun addGetRolePermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        storageInstance.addRole(developerRole)
        storageInstance.addPermissionToRole(developerRole, RolePermission.ADD_ROLE_PERMISSION)
        storageInstance.addPermissionToRole(developerRole, RolePermission.ADD_DEVELOPER)
        storageInstance.addPermissionToRole(developerRole, RolePermission.REMOVE_ROLE_PERMISSION)
        val rolePermissions = storageInstance.getRolePermissions(developerRole)
        assertEquals(3, rolePermissions.size)
        assertNotNull(rolePermissions.find { it.commandType == CommandType.LIVE_MANAGEMENT_SERVICE })
    }

    @Test
    fun removePermissionFromRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        storageInstance.addRole(developerRole)
        storageInstance.addPermissionToRole(developerRole, RolePermission.ADD_DEVELOPER_ROLE)
        val rolePermissions = storageInstance.getRolePermissions(developerRole)
        assertEquals(1, rolePermissions.size)
        assertNotNull(rolePermissions.find { it.commandType == CommandType.LIVE_MANAGEMENT_SERVICE })

        storageInstance.removePermissionFromRole(developerRole, RolePermission.ADD_DEVELOPER_ROLE)
        val updatedRolePermissions = storageInstance.getRolePermissions(developerRole)
        assertEquals(0, updatedRolePermissions.size)
        assertNull(updatedRolePermissions.find { it.commandType == CommandType.LIVE_MANAGEMENT_SERVICE })
    }

    @Test
    fun getRoleAccessPermissions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        storageInstance.addRole(developerRole)

        val id = testNameAsUniqueInstrumentId
        storageInstance.addAccessPermission(id, listOf("pattern"), AccessType.BLACK_LIST)
        val roleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        assertNull(roleAccessPermissions.find { it.id == id })

        storageInstance.addAccessPermissionToRole(id, developerRole)
        val updatedRoleAccessPermissions = storageInstance.getRoleAccessPermissions(developerRole)
        assertEquals(1, updatedRoleAccessPermissions.size)
        assertNotNull(updatedRoleAccessPermissions.find { it.id == id })

        val id1 = testNameAsUniqueInstrumentId
        val id2 = testNameAsUniqueInstrumentId
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
        storageInstance.addAccessPermission(testNameAsUniqueInstrumentId, listOf("pattern3"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermission(testNameAsUniqueInstrumentId, listOf("pattern4"), AccessType.WHITE_LIST)
        storageInstance.addAccessPermission(testNameAsUniqueInstrumentId, listOf("pattern5"), AccessType.BLACK_LIST)
        val accessPermissions = storageInstance.getAccessPermissions()
        assertTrue(accessPermissions.size >= 3)
    }

    @Test
    fun hasAccessPermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        assertFalse(storageInstance.hasAccessPermission(id))
        storageInstance.addAccessPermission(id, listOf("pattern6"), AccessType.WHITE_LIST)
        assertTrue(storageInstance.hasAccessPermission(id))
    }

    @Test
    fun getAccessPermission(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
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
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        storageInstance.addRole(developerRole)

        val id = testNameAsUniqueInstrumentId
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
        val id = testNameAsUniqueInstrumentId
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup1", "value1")
        val dataRedaction = storageInstance.getDataRedaction(id)
        assertNotNull(dataRedaction)
        assertEquals(id, dataRedaction!!.id)
        assertEquals(RedactionType.IDENTIFIER_MATCH, dataRedaction.type)
        assertEquals("lookup1", dataRedaction.lookup)
        assertEquals("value1", dataRedaction.replacement)
    }

    @Test
    fun hasDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        assertNull(storageInstance.getDataRedaction(id))
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup2", "value2")
        assertNotNull(storageInstance.getDataRedaction(id))
    }

    @Test
    fun getDataRedactions(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id3 = testNameAsUniqueInstrumentId
        val id4 = testNameAsUniqueInstrumentId
        val id5 = testNameAsUniqueInstrumentId
        storageInstance.addDataRedaction(id3, RedactionType.IDENTIFIER_MATCH, "lookup3", "value3")
        storageInstance.addDataRedaction(id4, RedactionType.VALUE_REGEX, "lookup4", "value4")
        storageInstance.addDataRedaction(id5, RedactionType.IDENTIFIER_MATCH, "lookup5", "value5")
        assertTrue(storageInstance.getDataRedactions().size >= 3)
    }

    @Test
    fun updateDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup1", "value1")
        val dataRedaction = storageInstance.getDataRedaction(id)
        assertNotNull(dataRedaction)
        assertEquals(id, dataRedaction!!.id)
        assertEquals(RedactionType.IDENTIFIER_MATCH, dataRedaction.type)
        assertEquals("lookup1", dataRedaction.lookup)
        assertEquals("value1", dataRedaction.replacement)

        storageInstance.updateDataRedaction(id, RedactionType.VALUE_REGEX, "lookup6", "value6")
        val updatedDataRedaction = storageInstance.getDataRedaction(id)
        assertNotNull(updatedDataRedaction)
        assertEquals(id, updatedDataRedaction!!.id)
        assertEquals(RedactionType.VALUE_REGEX, updatedDataRedaction.type)
        assertEquals("lookup6", updatedDataRedaction.lookup)
        assertEquals("value6", updatedDataRedaction.replacement)
    }

    @Test
    fun removeDataRedaction(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        storageInstance.addDataRedaction(id, RedactionType.IDENTIFIER_MATCH, "lookup7", "value7")
        assertNotNull(storageInstance.getDataRedaction(id))
        storageInstance.removeDataRedaction(id)
        assertNull(storageInstance.getDataRedaction(id))
    }

    @Test
    fun removeDataRedactionFromRole(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val redactionId = testNameAsUniqueInstrumentId
        storageInstance.addDataRedaction(redactionId, RedactionType.IDENTIFIER_MATCH, "lookup8", "value8")
        val developerRole = DeveloperRole.fromString(testNameAsUniqueInstrumentId)
        storageInstance.addRole(developerRole)
        storageInstance.addDataRedactionToRole(redactionId, developerRole)
        val dataRedactions = storageInstance.getRoleDataRedactions(developerRole)
        assertEquals(1, dataRedactions.size)
        assertEquals("value8", dataRedactions.toList()[0].replacement)

        storageInstance.removeDataRedactionFromRole(redactionId, developerRole)
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
        val id = testNameAsUniqueInstrumentId
        val secret = testNameAsUniqueInstrumentId
        assertNull(storageInstance.getClientAccess(id))

        storageInstance.addClientAccess(id, secret)
        val clientAccess = storageInstance.getClientAccess(id)
        assertNotNull(clientAccess)
        assertEquals(secret, clientAccess?.secret)
    }

    @Test
    fun removeClientAccess(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        storageInstance.addClientAccess(id)
        val clientAccess = storageInstance.getClientAccess(id)
        assertNotNull(clientAccess)
        assertTrue(storageInstance.getClientAccessors().isNotEmpty())

        assertTrue(storageInstance.removeClientAccess(id))
        assertNull(storageInstance.getClientAccess(id))
    }

    @Test
    fun refreshClientAccess(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val secret = testNameAsUniqueInstrumentId
        storageInstance.addClientAccess(id, secret)
        val clientAccess = storageInstance.getClientAccess(id)
        assertEquals(secret, clientAccess?.secret)

        storageInstance.refreshClientAccess(id)
        val updatedClientAccess = storageInstance.getClientAccess(id)
        assertNotEquals(secret, updatedClientAccess?.secret)
    }

    @Test
    fun addLiveInstrument(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file1", 1),
            id = id
        )
        storageInstance.addLiveInstrument(instrument)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstruments().find { it.id == id }?.toJson())
        assertTrue(storageInstance.removeLiveInstrument(id))
    }

    @Test
    fun getLiveInstrument(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file2", 2),
            id = id
        )
        storageInstance.addLiveInstrument(instrument)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())
        assertTrue(storageInstance.removeLiveInstrument(id))
    }

    @Test
    fun removeLiveInstrument(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file3", 3),
            id = id
        )
        storageInstance.addLiveInstrument(instrument)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())
        storageInstance.removeLiveInstrument(id)
        assertNull(storageInstance.getLiveInstrument(id))
    }

    @Test
    fun updateLiveInstrument(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file4", 1),
            id = id
        )
        storageInstance.addLiveInstrument(instrument)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())

        val updatedInstrument = LiveBreakpoint(
            location = LiveSourceLocation("file4", 1),
            id = id,
            applied = true
        )
        storageInstance.updateLiveInstrument(id, updatedInstrument)
        assertEquals(updatedInstrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())
        assertTrue(storageInstance.removeLiveInstrument(id))
    }

    @Test
    fun getLiveInstruments(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val breakpoint = LiveBreakpoint(
            location = LiveSourceLocation("file5", 1),
            id = testNameAsUniqueInstrumentId
        )
        val log = LiveLog(
            "Log Message",
            location = LiveSourceLocation("file6", 1),
            id = testNameAsUniqueInstrumentId
        )
        val meter = LiveMeter(
            MeterType.COUNT,
            MetricValue(MetricValueType.VALUE, "5"),
            location = LiveSourceLocation("file7", 1),
            id = testNameAsUniqueInstrumentId
        )
        val span = LiveSpan(
            "Span Name",
            location = LiveSourceLocation("file8", 1),
            id = testNameAsUniqueInstrumentId
        )

        storageInstance.addLiveInstrument(breakpoint)
        storageInstance.addLiveInstrument(log)
        storageInstance.addLiveInstrument(meter)
        storageInstance.addLiveInstrument(span)

        val instruments = storageInstance.getLiveInstruments()
        assertEquals(breakpoint.toJson(), instruments.first { it.id == breakpoint.id }.toJson())
        assertEquals(log.toJson(), instruments.first { it.id == log.id }.toJson())
        assertEquals(meter.toJson(), instruments.first { it.id == meter.id }.toJson())
        assertEquals(span.toJson(), instruments.first { it.id == span.id }.toJson())
        assertTrue(storageInstance.removeLiveInstrument(breakpoint.id!!))
        assertTrue(storageInstance.removeLiveInstrument(log.id!!))
        assertTrue(storageInstance.removeLiveInstrument(meter.id!!))
        assertTrue(storageInstance.removeLiveInstrument(span.id!!))
    }

    @Test
    fun `removed instruments get archived`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file11", 1),
            id = id
        )

        storageInstance.addLiveInstrument(instrument)
        assertTrue(storageInstance.getLiveInstruments().isNotEmpty())
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())

        val archiveSize = storageInstance.getArchivedLiveInstruments().size
        storageInstance.removeLiveInstrument(id)
        assertNull(storageInstance.getLiveInstrument(id))
        assertEquals(archiveSize + 1, storageInstance.getArchivedLiveInstruments().size)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id, true)!!.toJson())
    }

    @Test
    fun `updates to non-existent instruments fail`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file12", 1),
            id = id
        )

        try {
            storageInstance.updateLiveInstrument(id, instrument)
            fail("Expected exception")
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException)
        }
    }

    @Test
    fun `updates to archived instruments`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file13", 1),
            id = id
        )

        storageInstance.addLiveInstrument(instrument)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())

        val archiveSize = storageInstance.getArchivedLiveInstruments().size
        storageInstance.removeLiveInstrument(id)
        assertNull(storageInstance.getLiveInstrument(id))
        assertEquals(archiveSize + 1, storageInstance.getArchivedLiveInstruments().size)
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id, true)!!.toJson())

        val updatedInstrument = LiveBreakpoint(
            location = LiveSourceLocation("file13", 1),
            id = id,
            applied = true
        )
        storageInstance.updateLiveInstrument(id, updatedInstrument)

        assertNull(storageInstance.getLiveInstrument(id))
        assertEquals(updatedInstrument.toJson(), storageInstance.getLiveInstrument(id, true)!!.toJson())
    }

    @Test
    fun `get instrument events`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument = LiveBreakpoint(
            location = LiveSourceLocation("file14", 1),
            id = id
        )

        storageInstance.addLiveInstrument(instrument)
        assertTrue(storageInstance.getLiveInstruments().isNotEmpty())
        assertEquals(instrument.toJson(), storageInstance.getLiveInstrument(id)!!.toJson())

        val addedEvent = LiveInstrumentAdded(instrument)
        SourceStorage.addLiveInstrumentEvent(instrument, addedEvent)

        val events = storageInstance.getLiveInstrumentEvents(id)
        assertEquals(1, events.size)
        assertEquals(addedEvent.toJson(), events.first().toJson())
        assertTrue(storageInstance.removeLiveInstrument(id))
    }

    @Test
    fun `get instrument events by date range`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        val id = testNameAsUniqueInstrumentId
        val instrument1 = LiveBreakpoint(
            location = LiveSourceLocation("file15", 1),
            id = "$id-1"
        )
        storageInstance.addLiveInstrument(instrument1)
        val instrument2 = LiveBreakpoint(
            location = LiveSourceLocation("file15", 2),
            id = "$id-2"
        )
        storageInstance.addLiveInstrument(instrument2)
        val instrument3 = LiveBreakpoint(
            location = LiveSourceLocation("file15", 3),
            id = "$id-3"
        )
        storageInstance.addLiveInstrument(instrument3)

        val now = Instant.now()
        val addedEvent1 = LiveInstrumentAdded(instrument1, occurredAt = now.minusSeconds(10))
        SourceStorage.addLiveInstrumentEvent(instrument1, addedEvent1)
        val addedEvent2 = LiveInstrumentAdded(instrument2, occurredAt = now.minusSeconds(5))
        SourceStorage.addLiveInstrumentEvent(instrument2, addedEvent2)
        val addedEvent3 = LiveInstrumentAdded(instrument3, occurredAt = now)
        SourceStorage.addLiveInstrumentEvent(instrument3, addedEvent3)

        val events = storageInstance.getLiveInstrumentEvents(from = now.minusSeconds(6), to = now)
            .filter { it.instrument.id?.startsWith(id) == true }.toList()
        assertEquals(2, events.size)

        assertEquals(addedEvent3.toJson(), events[0].toJson())
        assertEquals(addedEvent2.toJson(), events[1].toJson())
        assertTrue(storageInstance.removeLiveInstrument(instrument1.id!!))
        assertTrue(storageInstance.removeLiveInstrument(instrument2.id!!))
        assertTrue(storageInstance.removeLiveInstrument(instrument3.id!!))
    }

    @Test
    fun `get non-existent live instrument`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertNull(storageInstance.getLiveInstrument("non-existent-id"))
    }

    @Test
    fun `remove non-existent live instrument`(vertx: Vertx): Unit = runBlocking(vertx.dispatcher()) {
        assertFalse(storageInstance.removeLiveInstrument("non-existent-id"))
    }
}
