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
package spp.platform.core

import integration.PlatformIntegrationTest
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.platform.core.api.GraphqlAPI
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.platform.auth.AccessType
import spp.protocol.platform.auth.DeveloperRole.Companion.ROLE_MANAGER
import spp.protocol.platform.auth.RedactionType
import spp.protocol.platform.auth.RolePermission

@Suppress("LargeClass", "TooManyFunctions") // public API test
class GraphqlAPIITTest : PlatformIntegrationTest() {

    companion object {
        lateinit var request: HttpRequest<Buffer>

        @BeforeAll
        @JvmStatic
        fun setupInit() {
            val client = WebClient.create(vertx(), WebClientOptions())
            request = client.post(platformPort, platformHost, "/graphql/spp")
                .bearerTokenAuthentication(systemAuthToken)
        }
    }

    private fun getGraphql(path: String): String {
        return GraphqlAPI::class.java.getResource("/graphql/$path.graphql")?.readText()
            ?: error("GraphQL file not found: $path")
    }

    @BeforeEach
    fun reset() = runBlocking {
        val resetResp = request
            .sendJsonObject(
                JsonObject().put("query", getGraphql("system/reset"))
            ).await().bodyAsJsonObject()
        assertTrue(resetResp.getJsonObject("data").getBoolean("reset"))
    }

    @Test
    fun `ensure getAccessPermissions works`() = runBlocking {
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))

        val getAccessPermissionsResp = request
            .sendJsonObject(JsonObject().put("query", getGraphql("access/get-access-permissions")))
            .await().bodyAsJsonObject()
        assertNull(getAccessPermissionsResp.getJsonArray("errors"))

        val accessPermission: JsonObject = getAccessPermissionsResp.getJsonObject("data")
            .getJsonArray("getAccessPermissions")[0]
        assertEquals(AccessType.WHITE_LIST.name, accessPermission.getString("type"))
        assertEquals("some-pattern", accessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure addAccessPermission works`() = runBlocking {
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        assertEquals(AccessType.WHITE_LIST.name, accessPermission.getString("type"))
        assertEquals("some-pattern", accessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure addDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))
        val dataRedaction = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        assertEquals("some-id", dataRedaction.getString("id"))
        assertEquals(RedactionType.VALUE_REGEX.name, dataRedaction.getString("type"))
        assertEquals("some-lookup", dataRedaction.getString("lookup"))
        assertEquals("some-replacement", dataRedaction.getString("replacement"))
    }

    @Test
    fun `ensure updateDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val updateDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    getGraphql("data-redaction/update-data-redaction")
                ).put(
                    "variables",
                    JsonObject().put("id", "some-id").put("type", RedactionType.IDENTIFIER_MATCH)
                        .put("lookup", "other-lookup")
                        .put("replacement", "other-replacement")
                )
            ).await().bodyAsJsonObject()
        assertNull(updateDataRedactionResp.getJsonArray("errors"))
        val updatedDataRedaction = updateDataRedactionResp.getJsonObject("data").getJsonObject("updateDataRedaction")
        assertEquals(RedactionType.IDENTIFIER_MATCH.name, updatedDataRedaction.getString("type"))
        assertEquals("other-lookup", updatedDataRedaction.getString("lookup"))
        assertEquals("other-replacement", updatedDataRedaction.getString("replacement"))
    }

    @Test
    fun `ensure getDataRedaction with ID works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val getDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("data-redaction/get-data-redaction")
                ).put("variables", JsonObject().put("id", "some-id"))
            ).await().bodyAsJsonObject()
        assertNull(getDataRedactionResp.getJsonArray("errors"))
        val updatedDataRedaction = getDataRedactionResp.getJsonObject("data").getJsonObject("getDataRedaction")
        assertEquals(RedactionType.VALUE_REGEX.name, updatedDataRedaction.getString("type"))
        assertEquals("some-lookup", updatedDataRedaction.getString("lookup"))
        assertEquals("some-replacement", updatedDataRedaction.getString("replacement"))
    }

    @Test
    fun `ensure getDataRedactions works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val getDataRedactionsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("data-redaction/get-data-redactions")
                )
            ).await().bodyAsJsonObject()
        assertNull(getDataRedactionsResp.getJsonArray("errors"))
        val dataRedactions = getDataRedactionsResp.getJsonObject("data").getJsonArray("getDataRedactions")
        assertTrue(dataRedactions.any {
            it as JsonObject
            it.getString("id").equals("some-id")
        })
        assertTrue(dataRedactions.any {
            it as JsonObject
            it.getString("type").equals(RedactionType.VALUE_REGEX.name)
        })
    }

    @Test
    fun `ensure removeDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val removeDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("data-redaction/remove-data-redaction")
                ).put("variables", JsonObject().put("id", "some-id"))
            ).await().bodyAsJsonObject()
        assertNull(removeDataRedactionResp.getJsonArray("errors"))
        assertTrue(removeDataRedactionResp.getJsonObject("data").getBoolean("removeDataRedaction"))
    }

    @Test
    fun `ensure getAccessPermission with ID works`() = runBlocking {
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))

        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = accessPermission.getString("id")
        val getAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("access/get-access-permission")
                ).put("variables", JsonObject().put("id", accessPermissionId))
            ).await().bodyAsJsonObject()
        assertNull(getAccessPermissionResp.getJsonArray("errors"))

        val accessPermission2: JsonObject = getAccessPermissionResp.getJsonObject("data")
            .getJsonObject("getAccessPermission")
        assertEquals(AccessType.WHITE_LIST.name, accessPermission2.getString("type"))
        assertEquals("some-pattern", accessPermission2.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure addDeveloper works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val developer: JsonObject = addDeveloperResp.getJsonObject("data").getJsonObject("addDeveloper")
        assertEquals("developer-id", developer.getString("id"))
        assertNotNull(developer.getString("accessToken"))
    }

    @Test
    fun `ensure refreshDeveloperToken works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val developer: JsonObject = addDeveloperResp.getJsonObject("data").getJsonObject("addDeveloper")
        val developerId = developer.getString("id")
        val developerAccessToken = developer.getString("accessToken")
        val getDevelopersResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("developer/refresh-developer-token"))
                .put("variables", JsonObject().put("id", developerId))
        ).await().bodyAsJsonObject()
        assertNull(getDevelopersResp.getJsonArray("errors"))

        val developerRefreshed = getDevelopersResp.getJsonObject("data").getJsonObject("refreshDeveloperToken")
        assertEquals(developerId, developerRefreshed.getString("id"))
        assertNotEquals(developerAccessToken, developerRefreshed.getString("accessToken"))
    }

    @Test
    fun `ensure getDevelopers works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val getDevelopersResp = request
            .sendJsonObject(
                JsonObject().put("query", getGraphql("developer/get-developers"))
            ).await().bodyAsJsonObject()
        assertNull(getDevelopersResp.getJsonArray("errors"))

        val developers = getDevelopersResp.getJsonObject("data").getJsonArray("getDevelopers")
        assertTrue(developers.any {
            it as JsonObject
            it.getString("id").equals("developer-id")
        })
    }

    @Test
    fun `ensure removeDeveloper works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val removeDeveloperResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("developer/remove-developer")
                ).put("variables", JsonObject().put("id", "developer-id"))
            ).await().bodyAsJsonObject()
        assertNull(removeDeveloperResp.getJsonArray("errors"))
        assertTrue(removeDeveloperResp.getJsonObject("data").getBoolean("removeDeveloper"))
    }

    @Test
    fun `ensure addRole works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))
        assertTrue(addRoleResp.getJsonObject("data").getBoolean("addRole"))
    }

    @Test
    fun `ensure getRoles works`() = runBlocking {
        val getRolesResp = request
            .sendJsonObject(
                JsonObject().put("query", getGraphql("role/get-roles"))
            ).await().bodyAsJsonObject()
        assertNull(getRolesResp.getJsonArray("errors"))
        val roles = getRolesResp.getJsonObject("data").getJsonArray("getRoles")
        assertTrue(roles.size() > 0)
    }

    @Test
    fun `ensure addRoleDataRedaction works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val addRoleDataRedactionResp = request.sendJsonObject(addRoleDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addRoleDataRedactionResp.getJsonArray("errors"))
        assertTrue(addRoleDataRedactionResp.getJsonObject("data").getBoolean("addRoleDataRedaction"))
    }

    @Test
    fun `ensure removeRoleDataRedaction works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val addRoleDataRedactionResp = request.sendJsonObject(addRoleDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addRoleDataRedactionResp.getJsonArray("errors"))

        val removeRoleDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("data-redaction/remove-role-data-redaction")
                ).put("variables", JsonObject().put("role", "developer-role").put("dataRedactionId", "some-id"))
            ).await().bodyAsJsonObject()
        assertNull(removeRoleDataRedactionResp.getJsonArray("errors"))
        assertTrue(removeRoleDataRedactionResp.getJsonObject("data").getBoolean("removeRoleDataRedaction"))
    }

    @Test
    fun `ensure getRoleDataRedactions works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val addRoleDataRedactionResp = request.sendJsonObject(addRoleDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addRoleDataRedactionResp.getJsonArray("errors"))
        assertTrue(addRoleDataRedactionResp.getJsonObject("data").getBoolean("addRoleDataRedaction"))

        val getRoleDataRedactionsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query", getGraphql("data-redaction/get-role-data-redaction")
                ).put("variables", JsonObject().put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        assertNull(getRoleDataRedactionsResp.getJsonArray("errors"))
        val roleDataRedactions = getRoleDataRedactionsResp.getJsonObject("data").getJsonArray("getRoleDataRedactions")
        assertTrue(roleDataRedactions.any {
            it as JsonObject
            it.getString("id").equals("some-id")
        })
        assertTrue(roleDataRedactions.any {
            it as JsonObject
            it.getString("type").equals("VALUE_REGEX")
        })
    }

    @Test
    fun `ensure getDeveloperDataRedactions works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addDataRedactionResp.getJsonArray("errors"))

        val addRoleDataRedactionResp = request.sendJsonObject(addRoleDataRedactionRequest).await().bodyAsJsonObject()
        assertNull(addRoleDataRedactionResp.getJsonArray("errors"))
        assertTrue(addRoleDataRedactionResp.getJsonObject("data").getBoolean("addRoleDataRedaction"))

        val getDeveloperDataRedactionsResp = request
            .sendJsonObject(
                JsonObject().put("query", getGraphql("data-redaction/get-developer-data-redaction"))
                    .put("variables", JsonObject().put("developerId", "developer-id"))
            ).await().bodyAsJsonObject()
        assertNull(getDeveloperDataRedactionsResp.getJsonArray("errors"))
        val developerDataRedactions =
            getDeveloperDataRedactionsResp.getJsonObject("data").getJsonArray("getDeveloperDataRedactions")
        assertTrue(developerDataRedactions.any {
            it as JsonObject
            it.getString("id").equals("some-id")
        })
        assertTrue(developerDataRedactions.any {
            it as JsonObject
            it.getString("type").equals("VALUE_REGEX")
        })
    }

    @Test
    fun `ensure removeRole works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val removeRoleResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("role/remove-role"))
                .put("variables", JsonObject().put("role", "developer-role"))
        ).await().bodyAsJsonObject()
        assertNull(removeRoleResp.getJsonArray("errors"))
        assertTrue(removeRoleResp.getJsonObject("data").getBoolean("removeRole"))
    }

    @Test
    fun `ensure addDeveloperRole works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperRoleResp.getJsonArray("errors"))
        assertTrue(addDeveloperRoleResp.getJsonObject("data").getBoolean("addDeveloperRole"))
    }

    @Test
    fun `ensure getDeveloperRoles works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val getDeveloperRolesResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("role/get-developer-roles"))
                .put("variables", JsonObject().put("id", "developer-id"))
        ).await().bodyAsJsonObject()
        assertNull(getDeveloperRolesResp.getJsonArray("errors"))
    }

    @Test
    fun `ensure getDeveloperPermissions works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val addRolePermissionResp = request.sendJsonObject(addRolePermissionRequest).await().bodyAsJsonObject()
        assertNull(addRolePermissionResp.getJsonArray("errors"))

        val getDeveloperPermissionsResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("permission/get-developer-permissions"))
                .put("variables", JsonObject().put("id", "developer-id"))
        ).await().bodyAsJsonObject()
        assertNull(getDeveloperPermissionsResp.getJsonArray("errors"))
        val developerPermissions =
            getDeveloperPermissionsResp.getJsonObject("data").getJsonArray("getDeveloperPermissions")
        assertTrue(developerPermissions.any {
            it as String
            it.equals(RolePermission.ADD_ROLE.name)
        })
    }

    @Test
    fun `ensure removeDeveloperRole works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val removeDeveloperRoleResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("role/remove-developer-role"))
                .put("variables", JsonObject().put("id", "developer-id").put("role", "developer-role"))
        ).await().bodyAsJsonObject()
        assertNull(removeDeveloperRoleResp.getJsonArray("errors"))
        assertTrue(removeDeveloperRoleResp.getJsonObject("data").getBoolean("removeDeveloperRole"))
    }

    @Test
    fun `ensure getRoleAccessPermissions works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = accessPermission.getString("id")

        val addRoleAccessPermissionResp =
            request.sendJsonObject(addRoleAccessPermissionRequest(accessPermissionId)).await().bodyAsJsonObject()
        assertNull(addRoleAccessPermissionResp.getJsonArray("errors"))

        val getRoleAccessPermissions = request.sendJsonObject(
            JsonObject().put("query", getGraphql("access/get-role-access-permissions"))
                .put("variables", JsonObject().put("role", "developer-role"))
        ).await().bodyAsJsonObject()
        assertNull(getRoleAccessPermissions.getJsonArray("errors"))
        val roleAccessPermission: JsonObject = getRoleAccessPermissions.getJsonObject("data")
            .getJsonArray("getRoleAccessPermissions")[0]
        assertEquals(AccessType.WHITE_LIST.name, roleAccessPermission.getString("type"))
        assertEquals("some-pattern", roleAccessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure removeRoleAccessPermission works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = accessPermission.getString("id")

        //todo: role is not checked if it has the accessPermissionId
//        val addRoleAccessPermissionResp =
//            request.sendJsonObject(addRoleAccessPermissionRequest(accessPermissionId)).await().bodyAsJsonObject()
//        Assertions.assertNull(addRoleAccessPermissionResp.getJsonArray("errors"))

        val removeRoleAccessPermissionResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("access/remove-role-access-permission")).put(
                "variables",
                JsonObject().put("role", "developer-role").put("accessPermissionId", accessPermissionId)
            )
        ).await().bodyAsJsonObject()
        assertNull(removeRoleAccessPermissionResp.getJsonArray("errors"))
        assertTrue(
            removeRoleAccessPermissionResp.getJsonObject("data").getBoolean("removeRoleAccessPermission")
        )
    }

    @Test
    fun `ensure getDeveloperAccessPermissions works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = accessPermission.getString("id")

        val addRoleAccessPermissionResp =
            request.sendJsonObject(addRoleAccessPermissionRequest(accessPermissionId)).await().bodyAsJsonObject()
        assertNull(addRoleAccessPermissionResp.getJsonArray("errors"))

        val getDeveloperAccessPermissions = request.sendJsonObject(
            JsonObject().put("query", getGraphql("access/get-developer-access-permissions"))
                .put("variables", JsonObject().put("developerId", "developer-id"))
        ).await().bodyAsJsonObject()
        assertNull(getDeveloperAccessPermissions.getJsonArray("errors"))
        val developerAccessPermission: JsonObject = getDeveloperAccessPermissions.getJsonObject("data")
            .getJsonArray("getDeveloperAccessPermissions")[0]
        assertEquals(AccessType.WHITE_LIST.name, developerAccessPermission.getString("type"))
        assertEquals("some-pattern", developerAccessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure removeAccessPermission works`() = runBlocking {
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")

        val accessPermissionId = accessPermission.getString("id")
        val removeAccessPermission = request.sendJsonObject(
            JsonObject().put("query", getGraphql("access/remove-access-permission"))
                .put("variables", JsonObject().put("id", accessPermissionId))
        ).await().bodyAsJsonObject()
        assertNull(removeAccessPermission.getJsonArray("errors"))
        assertTrue(removeAccessPermission.getJsonObject("data").getBoolean("removeAccessPermission"))
    }

    @Test
    fun `ensure all role permissions are known`() = runBlocking {
        val knownRolePermissions = managementService.getRolePermissions(ROLE_MANAGER).await()
        RolePermission.values().forEach {
            assert(knownRolePermissions.contains(it)) {
                "Role permission $it is not known"
            }
        }

        val getRolePermissionsResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("permission/get-role-permissions"))
                .put("variables", JsonObject().put("role", ROLE_MANAGER.roleName))
        ).await().bodyAsJsonObject()
        assertNull(getRolePermissionsResp.getJsonArray("errors"))
        val getRolePermissions = getRolePermissionsResp.getJsonObject("data").getJsonArray("getRolePermissions")
        RolePermission.values().forEach {
            assert(getRolePermissions.contains(it.name)) {
                "Role permission $it is not known"
            }
        }
    }

    @Test
    fun `ensure addRolePermission works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addRolePermissionResp = request.sendJsonObject(addRolePermissionRequest).await().bodyAsJsonObject()
        assertNull(addRolePermissionResp.getJsonArray("errors"))
        assertTrue(addRolePermissionResp.getJsonObject("data").getBoolean("addRolePermission"))
    }

    @Test
    fun `ensure addLiveView works`() = runBlocking {
        val addLiveViewResp =
            request.sendJsonObject(addLiveViewRequest).await().bodyAsJsonObject()
        assertNull(addLiveViewResp.getJsonArray("errors"))
        val liveView =
            addLiveViewResp.getJsonObject("data").getJsonObject("addLiveView")
        assertNotNull(liveView.getString("subscriptionId"))
        val entityIds = liveView.getJsonArray("entityIds")
        assertEquals(3, entityIds.size())
        assertTrue(entityIds.contains("1"))
        assertTrue(entityIds.contains("222"))
        assertTrue(entityIds.contains("3"))

        val viewConfig = liveView.getJsonObject("viewConfig")
        assertEquals("test", viewConfig.getString("viewName"))
        val viewMetrics = viewConfig.getJsonArray("viewMetrics")
        assertEquals(1, viewMetrics.size())
        assertEquals("test-metric", viewMetrics.getString(0))
        assertEquals(-1, viewConfig.getInteger("refreshRateLimit"))
    }

    @Test
    fun `ensure addLiveBreakpoint works`() = runBlocking {
        val addLiveBreakpointResp = request.sendJsonObject(addLiveBreakpointRequest).await().bodyAsJsonObject()
        assertNull(addLiveBreakpointResp.getJsonArray("errors"))
        val liveBreakpoint = addLiveBreakpointResp.getJsonObject("data").getJsonObject("addLiveBreakpoint")
        val liveBreakpointLocation = liveBreakpoint.getJsonObject("location")
        assertEquals("doing", liveBreakpointLocation.getString("source"))
        assertEquals(17, liveBreakpointLocation.getInteger("line"))
    }

    @Test
    fun `ensure getLiveBreakpoints works`() = runBlocking {
        val addLiveBreakpointResp = request.sendJsonObject(addLiveBreakpointRequest).await().bodyAsJsonObject()
        assertNull(addLiveBreakpointResp.getJsonArray("errors"))

        val getLiveBreakpointsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("instrument/get-live-breakpoints")))
                .await().bodyAsJsonObject()
        assertNull(getLiveBreakpointsResp.getJsonArray("errors"))
        val liveBreakpoints = getLiveBreakpointsResp.getJsonObject("data").getJsonArray("getLiveBreakpoints")
        assertTrue(liveBreakpoints.any {
            it as JsonObject
            it.getJsonObject("location").getString("source").equals("doing")
        })
        assertTrue(liveBreakpoints.any {
            it as JsonObject
            it.getJsonObject("location").getInteger("line").equals(17)
        })
    }

    @Test
    fun `ensure addLiveLog works`() = runBlocking {
        val addLiveLogResp = request.sendJsonObject(addLiveLogRequest).await().bodyAsJsonObject()
        assertNull(addLiveLogResp.getJsonArray("errors"))
        val liveLog = addLiveLogResp.getJsonObject("data").getJsonObject("addLiveLog")
        val liveLogLocation = liveLog.getJsonObject("location")
        assertEquals("doing", liveLogLocation.getString("source"))
        assertEquals(19, liveLogLocation.getInteger("line"))
    }

    @Test
    fun `ensure getLiveLogs works`() = runBlocking {
        val addLiveLogResp = request.sendJsonObject(addLiveLogRequest).await().bodyAsJsonObject()
        assertNull(addLiveLogResp.getJsonArray("errors"))

        val getLiveLogsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("instrument/get-live-logs")))
                .await().bodyAsJsonObject()
        assertNull(getLiveLogsResp.getJsonArray("errors"))

        val liveLogs = getLiveLogsResp.getJsonObject("data").getJsonArray("getLiveLogs")
        assertTrue(liveLogs.any {
            it as JsonObject
            it.getJsonObject("location").getString("source").equals("doing")
        })
        assertTrue(liveLogs.any {
            it as JsonObject
            it.getJsonObject("location").getInteger("line").equals(19)
        })
    }

    @Test
    fun `ensure addLiveSpan works`() = runBlocking {
        val addLiveSpanResp = request.sendJsonObject(addLiveSpanRequest).await().bodyAsJsonObject()
        assertNull(addLiveSpanResp.getJsonArray("errors"))
        val liveSpan = addLiveSpanResp.getJsonObject("data").getJsonObject("addLiveSpan")
        assertEquals("doing", liveSpan.getJsonObject("location").getString("source"))
        assertEquals("name-of-operation", liveSpan.getString("operationName"))
    }

    @Test
    fun `ensure getLiveSpans works`() = runBlocking {
        val addLiveSpanResp = request.sendJsonObject(addLiveSpanRequest).await().bodyAsJsonObject()
        assertNull(addLiveSpanResp.getJsonArray("errors"))

        val getLiveSpansResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("instrument/get-live-spans"))).await()
                .bodyAsJsonObject()
        assertNull(getLiveSpansResp.getJsonArray("errors"))
        val liveSpans = getLiveSpansResp.getJsonObject("data").getJsonArray("getLiveSpans")
        assertTrue(liveSpans.any {
            it as JsonObject
            it.getJsonObject("location").getString("source").equals("doing")
        })
        assertTrue(liveSpans.any {
            it as JsonObject
            it.getString("operationName").equals("name-of-operation")
        })
    }

    @Test
    fun `ensure addLiveMeter works`() = runBlocking {
        val addLiveMeterResp = request.sendJsonObject(addLiveMeterRequest).await().bodyAsJsonObject()
        assertNull(addLiveMeterResp.getJsonArray("errors"))
        val liveMeter = addLiveMeterResp.getJsonObject("data").getJsonObject("addLiveMeter")
        val liveMeterLocation = liveMeter.getJsonObject("location")
        assertEquals("doing", liveMeterLocation.getString("source"))
        assertEquals(19, liveMeterLocation.getInteger("line"))
    }

    @Test
    fun `ensure getLiveMeters works`() = runBlocking {
        val addLiveMeterResp = request.sendJsonObject(addLiveMeterRequest).await().bodyAsJsonObject()
        assertNull(addLiveMeterResp.getJsonArray("errors"))

        val getLiveMetersResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("instrument/get-live-meters")))
                .await().bodyAsJsonObject()
        assertNull(getLiveMetersResp.getJsonArray("errors"))
        val liveMeters = getLiveMetersResp.getJsonObject("data").getJsonArray("getLiveMeters")
        assertTrue(liveMeters.any {
            it as JsonObject
            it.getJsonObject("location").getString("source").equals("doing")
        })
        assertTrue(liveMeters.any {
            it as JsonObject
            it.getJsonObject("location").getInteger("line").equals(19)
        })
    }

    @Test
    fun `ensure getSelf works`() = runBlocking {
        val getSelfResp = request.sendJsonObject(JsonObject().put("query", getGraphql("developer/get-self")))
            .await().bodyAsJsonObject()
        assertNull(getSelfResp.getJsonArray("errors"))
        val selfInfo = getSelfResp.getJsonObject("data").getJsonObject("getSelf")
        assertEquals("system", selfInfo.getJsonObject("developer").getString("id"))
    }

    @Test
    fun `ensure getServices works`() = runBlocking {
        val getServicesResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("system/get-services"))).await()
                .bodyAsJsonObject()
        assertNull(getServicesResp.getJsonArray("errors"))
        val services = getServicesResp.getJsonObject("data").getJsonArray("getServices")
        for (i in 0 until services.size()) {
            val service = services.getJsonObject(i)
            assertNotNull(service.getString("id"))
            assertNotNull(service.getString("name"))
        }
    }

    @Test
    fun `ensure getLiveViews works`() = runBlocking {
        val addLiveViewResp =
            request.sendJsonObject(addLiveViewRequest).await().bodyAsJsonObject()
        assertNull(addLiveViewResp.getJsonArray("errors"))

        val getLiveViewsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("view/get-live-views"))).await()
                .bodyAsJsonObject()
        assertNull(getLiveViewsResp.getJsonArray("errors"))

        val liveViews =
            getLiveViewsResp.getJsonObject("data").getJsonArray("getLiveViews")
        assertFalse(liveViews.isEmpty)
        assertTrue(liveViews.any { it1 ->
            it1 as JsonObject
            val entityIds = it1.getJsonArray("entityIds")
            entityIds.any { it2 ->
                it2 as String
                it2 == "222"
            }
        })
    }

    @Test
    fun `ensure getLiveInstruments works`() = runBlocking {
        val addLiveMeterResp = request.sendJsonObject(addLiveMeterRequest).await().bodyAsJsonObject()
        assertNull(addLiveMeterResp.getJsonArray("errors"))

        val getLiveInstrumentsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("instrument/get-live-instruments")))
                .await().bodyAsJsonObject()
        assertNull(getLiveInstrumentsResp.getJsonArray("errors"))

        val liveViews = getLiveInstrumentsResp.getJsonObject("data").getJsonArray("getLiveInstruments")
        assertFalse(liveViews.isEmpty)
    }

    @Test
    fun `ensure clearLiveViews works`() = runBlocking {
        val clearLiveViewsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("view/clear-live-views")))
                .await().bodyAsJsonObject()
        assertNull(clearLiveViewsResp.getJsonArray("errors"))
        assertTrue(
            clearLiveViewsResp.getJsonObject("data").getBoolean("clearLiveViews")
        )
    }

    //todo: does not validate if LiveInstrument with ID exist
    @Test
    fun `ensure removeLiveInstrument works`() = runBlocking {
        val addLiveMeterResp = request.sendJsonObject(addLiveMeterRequest).await().bodyAsJsonObject()
        assertNull(addLiveMeterResp.getJsonArray("errors"))
        val liveMeter = addLiveMeterResp.getJsonObject("data").getJsonObject("addLiveMeter")
        val liveMeterId = liveMeter.getString("id")

        val removeLiveInstrumentResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("instrument/remove-live-instrument"))
                .put("variables", JsonObject().put("id", liveMeterId))
        ).await().bodyAsJsonObject()
        assertNull(removeLiveInstrumentResp.getJsonArray("errors"))
        val liveInstrument = removeLiveInstrumentResp.getJsonObject("data").getJsonObject("removeLiveInstrument")
        assertEquals(liveMeterId, liveInstrument.getString("id"))
    }

    //todo: this endpoint seem to retrieving objects
    //needs verification
    @Test
    fun `ensure removeLiveInstruments works`() = runBlocking {
        val addLiveMeterResp = request.sendJsonObject(addLiveMeterRequest).await().bodyAsJsonObject()
        assertNull(addLiveMeterResp.getJsonArray("errors"))
        val liveMeter = addLiveMeterResp.getJsonObject("data").getJsonObject("addLiveMeter")
        val liveMeterId = liveMeter.getString("id")

        val removeLiveInstrumentsResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("instrument/remove-live-instruments"))
                .put("variables", JsonObject().put("source", "doing").put("line", 19))
        ).await().bodyAsJsonObject()
        assertNull(removeLiveInstrumentsResp.getJsonArray("errors"))
        val removedLiveInstruments =
            removeLiveInstrumentsResp.getJsonObject("data").getJsonArray("removeLiveInstruments")
        assertTrue(removedLiveInstruments.any {
            it as JsonObject
            it.getString("id").equals(liveMeterId)
        })
    }

    @Test
    fun `ensure clearLiveInstruments works`() = runBlocking {
        val clearLiveInstrumentsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("instrument/clear-live-instruments")))
                .await().bodyAsJsonObject()
        assertNull(clearLiveInstrumentsResp.getJsonArray("errors"))
        assertNotNull(clearLiveInstrumentsResp.getJsonObject("data").getBoolean("clearLiveInstruments"))
    }

    @Test
    fun `ensure removeRolePermission works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        assertNull(addRoleResp.getJsonArray("errors"))

        val addRolePermissionResp = request.sendJsonObject(addRolePermissionRequest).await().bodyAsJsonObject()
        assertNull(addRolePermissionResp.getJsonArray("errors"))
        assertTrue(addRolePermissionResp.getJsonObject("data").getBoolean("addRolePermission"))

        val removeRolePermissionResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("permission/remove-role-permission"))
                .put("variables", JsonObject().put("role", "developer-role").put("permission", RolePermission.ADD_ROLE))
        ).await().bodyAsJsonObject()
        assertNull(removeRolePermissionResp.getJsonArray("errors"))
        assertTrue(removeRolePermissionResp.getJsonObject("data").getBoolean("removeRolePermission"))
    }

    @Test
    fun `ensure default-test client accessor is present`() = runBlocking {
        val testClientAccessList = managementService.getClientAccessors().await()
        assertNotNull(testClientAccessList.find { it.id == "test-id" })
        assertNotNull(testClientAccessList.find { it.secret == "test-secret" })

        val getClientAccessorsResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("client/get-client-accessors"))).await()
                .bodyAsJsonObject()
        assertNull(getClientAccessorsResp.getJsonArray("errors"))
        val testClientAccessJsonArray = getClientAccessorsResp.getJsonObject("data").getJsonArray("getClientAccessors")
        assertTrue(testClientAccessJsonArray.any {
            it as JsonObject
            it.getString("id").equals("test-id")
        })
        assertTrue(testClientAccessJsonArray.any {
            it as JsonObject
            it.getString("secret").equals("test-secret")
        })
    }

    //todo: No GraphQL for this endpoint
    @Test
    fun `ensure getting client access works`() = runBlocking {
        val clientAccess = managementService.getClientAccess("test-id").await()
        assertEquals("test-id", clientAccess?.id)
        assertEquals("test-secret", clientAccess?.secret)
    }

    //todo: does not accept custom id nor secret
    @Test
    fun `ensure adding new client accessor works`() = runBlocking {
        val generatedClientAccess = managementService.addClientAccess().await()
        assertNotNull(generatedClientAccess.id)
        assertNotNull(generatedClientAccess.secret)

        val addClientAccessResp =
            request.sendJsonObject(JsonObject().put("query", getGraphql("client/add-client-access")))
                .await().bodyAsJsonObject()
        assertNull(addClientAccessResp.getJsonArray("errors"))
        val generatedClientAccessGql = addClientAccessResp.getJsonObject("data").getJsonObject("addClientAccess")
        assertNotNull(generatedClientAccessGql.getString("id"))
        assertNotNull(generatedClientAccessGql.getString("secret"))
    }

    @Test
    fun `ensure remove client access works`() = runBlocking {
        val clientAccessList = managementService.getClientAccessors().await()
        assertNotNull(clientAccessList.find { it.id == "test-id" })

        assertTrue(managementService.removeClientAccess("test-id").await())

        val removedClientAccessList = managementService.getClientAccessors().await()
        assertNull(removedClientAccessList.find { it.id == "test-id" })

        val clientId = managementService.addClientAccess().await().id
        val addedClientAccess = managementService.getClientAccessors().await()
        assertNotNull(addedClientAccess.find { it.id == clientId })

        val removeClientAccessResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("client/remove-client-access"))
                .put("variables", JsonObject().put("id", clientId))
        ).await().bodyAsJsonObject()
        assertNull(removeClientAccessResp.getJsonArray("errors"))

        assertTrue(removeClientAccessResp.getJsonObject("data").getBoolean("removeClientAccess"))

        val removedClientAccessListGql = managementService.getClientAccessors().await()
        assertNull(removedClientAccessListGql.find { it.id == clientId })
    }

    @Test
    fun `ensure refresh client access works`() = runBlocking {
        val clientAccess = managementService.addClientAccess().await()
        val clientId = clientAccess.id
        val clientSecret = clientAccess.secret

        val updatedClientAccess = managementService.refreshClientAccess(clientId).await()
        assertEquals(clientId, updatedClientAccess.id)
        assertNotEquals(clientSecret, updatedClientAccess.secret)

        val refreshClientAccessResp = request.sendJsonObject(
            JsonObject().put("query", getGraphql("client/refresh-client-access"))
                .put("variables", JsonObject().put("id", clientId))
        ).await().bodyAsJsonObject()
        assertNull(refreshClientAccessResp.getJsonArray("errors"))

        val updatedClientAccessJsonObject =
            refreshClientAccessResp.getJsonObject("data").getJsonObject("refreshClientAccess")
        assertEquals(clientId, updatedClientAccessJsonObject.getString("id"))
        assertNotEquals(clientSecret, updatedClientAccessJsonObject.getString("secret"))
    }

    private val addDataRedactionRequest: JsonObject =
        JsonObject().put("query", getGraphql("data-redaction/add-data-redaction")).put(
            "variables",
            JsonObject().put("id", "some-id").put("type", RedactionType.VALUE_REGEX).put("lookup", "some-lookup")
                .put("replacement", "some-replacement")
        )

    private val addRoleDataRedactionRequest =
        JsonObject().put("query", getGraphql("data-redaction/add-role-data-redaction"))
            .put("variables", JsonObject().put("role", "developer-role").put("dataRedactionId", "some-id"))

    private val addAccessPermissionRequest: JsonObject =
        JsonObject().put("query", getGraphql("access/add-access-permission")).put(
            "variables", JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
        )

    private val addDeveloperRequest: JsonObject = JsonObject().put("query", getGraphql("developer/add-developer"))
        .put("variables", JsonObject().put("id", "developer-id"))

    private val addDeveloperRoleRequest: JsonObject =
        JsonObject().put("query", getGraphql("role/add-developer-role"))
            .put("variables", JsonObject().put("id", "developer-id").put("role", "developer-role"))

    private val addRoleRequest: JsonObject = JsonObject().put("query", getGraphql("role/add-role"))
        .put("variables", JsonObject().put("role", "developer-role"))

    private val addRolePermissionRequest = JsonObject().put("query", getGraphql("permission/add-role-permission"))
        .put("variables", JsonObject().put("role", "developer-role").put("permission", RolePermission.ADD_ROLE))

    private fun addRoleAccessPermissionRequest(accessPermissionId: String): JsonObject {
        return JsonObject().put("query", getGraphql("access/add-role-access-permission"))
            .put("variables", JsonObject().put("accessPermissionId", accessPermissionId).put("role", "developer-role"))
    }

    private val addLiveViewRequest =
        JsonObject().put("query", getGraphql("view/add-live-view")).put(
            "variables", JsonObject().put(
                "input", mapOf(
                    "entityIds" to listOf(1, 222, 3),
                    "viewConfig" to mapOf(
                        "viewName" to "test",
                        "viewMetrics" to listOf("test-metric")
                    )
                )
            )
        )

    private val addLiveBreakpointRequest: JsonObject =
        JsonObject().put("query", getGraphql("instrument/add-live-breakpoint"))
            .put("variables", JsonObject().put("input", mapOf("location" to mapOf("source" to "doing", "line" to 17))))

    private val addLiveLogRequest = JsonObject().put("query", getGraphql("instrument/add-live-log")).put(
        "variables",
        JsonObject().put(
            "input",
            mapOf("location" to mapOf("source" to "doing", "line" to 19), "logFormat" to "formatting")
        )
    )

    private val addLiveSpanRequest = JsonObject().put("query", getGraphql("instrument/add-live-span")).put(
        "variables",
        JsonObject().put(
            "input",
            mapOf("location" to mapOf("source" to "doing"), "operationName" to "name-of-operation")
        )
    )

    private val addLiveMeterRequest = JsonObject().put("query", getGraphql("instrument/add-live-meter")).put(
        "variables",
        JsonObject().put(
            "input",
            mapOf(
                "location" to mapOf("source" to "doing", "line" to 19),
                "meterType" to MeterType.COUNT, "metricValue" to MetricValue(MetricValueType.NUMBER, "3")
            )
        )
    )
}
