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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.protocol.platform.auth.AccessType
import spp.protocol.platform.auth.DeveloperRole.Companion.ROLE_MANAGER
import spp.protocol.platform.auth.RedactionType
import spp.protocol.platform.auth.RolePermission

class SourceServiceITTest : PlatformIntegrationTest() {

    companion object {
        lateinit var request: HttpRequest<Buffer>

        @BeforeAll
        @JvmStatic
        fun setupInit() {
            val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
            val client = WebClient.create(
                vertx(),
                WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
            )
            request = client.post(12800, platformHost, "/graphql/spp")
                .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
        }
    }

    @BeforeEach
    fun `reset`() = runBlocking {
        val resetResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """ mutation {
                          reset
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertTrue(resetResp.getJsonObject("data").getBoolean("reset"))
    }

    @Test
    fun `ensure getAccessPermissions works`() = runBlocking {
        val addAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}type: AccessType!, ${'$'}locationPatterns: [String!]){
                          addAccessPermission (type: ${'$'}type, locationPatterns: ${'$'}locationPatterns){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val addAccessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, addAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", addAccessPermission.getJsonArray("locationPatterns")[0])

        val getAccessPermissionsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query {
                          getAccessPermissions {
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getAccessPermissionsResp.getJsonArray("errors"))
        val accessPermission: JsonObject = getAccessPermissionsResp.getJsonObject("data")
            .getJsonArray("getAccessPermissions")[0]
        Assertions.assertEquals(AccessType.WHITE_LIST.name, accessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", accessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure addAccessPermission works`() = runBlocking {
        val addAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}type: AccessType!, ${'$'}locationPatterns: [String!]){
                          addAccessPermission (type: ${'$'}type, locationPatterns: ${'$'}locationPatterns){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val addAccessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, addAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", addAccessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure addDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}type: RedactionType!, ${'$'}lookup: String!, ${'$'}replacement: String!){
                          addDataRedaction (id: ${'$'}id, type: ${'$'}type, lookup: ${'$'}lookup, replacement: ${'$'}replacement){
                                id
                                type
                                lookup
                                replacement
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("id", "some-id").put("type", RedactionType.VALUE_REGEX)
                        .put("lookup", "some-lookup")
                        .put("replacement", "some-replacement")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val addAccessPermission = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", addAccessPermission.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, addAccessPermission.getString("type"))
        Assertions.assertEquals("some-lookup", addAccessPermission.getString("lookup"))
        Assertions.assertEquals("some-replacement", addAccessPermission.getString("replacement"))
    }

    @Test
    fun `ensure updateDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}type: RedactionType!, ${'$'}lookup: String!, ${'$'}replacement: String!){
                          addDataRedaction (id: ${'$'}id, type: ${'$'}type, lookup: ${'$'}lookup, replacement: ${'$'}replacement){
                                id
                                type
                                lookup
                                replacement
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("id", "some-id").put("type", RedactionType.VALUE_REGEX)
                        .put("lookup", "some-lookup")
                        .put("replacement", "some-replacement")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val addAccessPermission = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", addAccessPermission.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, addAccessPermission.getString("type"))

        val updateDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}type: RedactionType!, ${'$'}lookup: String!, ${'$'}replacement: String!){
                          updateDataRedaction (id: ${'$'}id, type: ${'$'}type, lookup: ${'$'}lookup, replacement: ${'$'}replacement){
                                id
                                type
                                lookup
                                replacement
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("id", "some-id").put("type", RedactionType.IDENTIFIER_MATCH)
                        .put("lookup", "other-lookup")
                        .put("replacement", "other-replacement")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(updateDataRedactionResp.getJsonArray("errors"))
        val updatedDataRedaction = updateDataRedactionResp.getJsonObject("data").getJsonObject("updateDataRedaction")
        Assertions.assertEquals(RedactionType.IDENTIFIER_MATCH.name, updatedDataRedaction.getString("type"))
        Assertions.assertEquals("other-lookup", updatedDataRedaction.getString("lookup"))
        Assertions.assertEquals("other-replacement", updatedDataRedaction.getString("replacement"))
    }

    //todeo: fails with Exception in thread "vert.x-eventloop-thread-1" java.lang.NullPointerException
    //	at spp.platform.storage.RedisStorage.getDataRedaction$suspendImpl(RedisStorage.kt:203)
//    @Test
//    fun `ensure getDataRedaction with ID works`() = runBlocking {
//        val addDataRedactionResp = request
//            .sendJsonObject(
//                JsonObject().put(
//                    "query",
//                    """mutation (${'$'}id: String!, ${'$'}type: RedactionType!, ${'$'}lookup: String!, ${'$'}replacement: String!){
//                          addDataRedaction (id: ${'$'}id, type: ${'$'}type, lookup: ${'$'}lookup, replacement: ${'$'}replacement){
//                                id
//                                type
//                                lookup
//                                replacement
//                            }
//                        }
//                    """.trimIndent()
//                ).put(
//                    "variables",
//                    JsonObject().put("id", "some-id").put("type", RedactionType.VALUE_REGEX)
//                        .put("lookup", "some-lookup")
//                        .put("replacement", "some-replacement")
//                )
//            ).await().bodyAsJsonObject()
//        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
//        val addAccessPermission = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
//        Assertions.assertEquals("some-id", addAccessPermission.getString("id"))
//        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, addAccessPermission.getString("type"))
//
//        val getDataRedactionResp = request
//            .sendJsonObject(
//                JsonObject().put(
//                    "query",
//                    """query (${'$'}id: String!){
//                          getDataRedaction (id: ${'$'}id){
//                                id
//                                type
//                                lookup
//                                replacement
//                            }
//                        }
//                    """.trimIndent()
//                ).put("variables", JsonObject().put("id", "other-id"))
//            ).await().bodyAsJsonObject()
//        Assertions.assertNull(getDataRedactionResp.getJsonArray("errors"))
//        val updatedDataRedaction = getDataRedactionResp.getJsonObject("data").getJsonObject("addAccessPermission")
//        Assertions.assertEquals(AccessType.WHITE_LIST.name, updatedDataRedaction.getString("type"))
//        Assertions.assertEquals("some-pattern", updatedDataRedaction.getJsonArray("locationPatterns")[0])
//    }

    @Test
    fun `ensure getDataRedactions works`() = runBlocking {
        val addDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}type: RedactionType!, ${'$'}lookup: String!, ${'$'}replacement: String!){
                          addDataRedaction (id: ${'$'}id, type: ${'$'}type, lookup: ${'$'}lookup, replacement: ${'$'}replacement){
                                id
                                type
                                lookup
                                replacement
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("id", "some-id").put("type", RedactionType.VALUE_REGEX)
                        .put("lookup", "some-lookup").put("replacement", "some-replacement")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val addAccessPermission = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", addAccessPermission.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, addAccessPermission.getString("type"))

        val getDataRedactionsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query {
                          getDataRedactions {
                                id
                                type
                                lookup
                                replacement
                            }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "some-id"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getDataRedactionsResp.getJsonArray("errors"))
        val dataRedactions = getDataRedactionsResp.getJsonObject("data").getJsonArray("getDataRedactions")
        Assertions.assertTrue(dataRedactions.size() > 0)
    }

    @Test
    fun `ensure removeDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}type: RedactionType!, ${'$'}lookup: String!, ${'$'}replacement: String!){
                          addDataRedaction (id: ${'$'}id, type: ${'$'}type, lookup: ${'$'}lookup, replacement: ${'$'}replacement){
                                id
                                type
                                lookup
                                replacement
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("id", "some-id").put("type", RedactionType.VALUE_REGEX)
                        .put("lookup", "some-lookup")
                        .put("replacement", "some-replacement")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val addAccessPermission = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", addAccessPermission.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, addAccessPermission.getString("type"))

        val removeDataRedactionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!){
                          removeDataRedaction (id: ${'$'}id)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "some-id"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeDataRedactionResp.getJsonArray("errors"))
        Assertions.assertTrue(removeDataRedactionResp.getJsonObject("data").getBoolean("removeDataRedaction"))
    }

    @Test
    fun `ensure getAccessPermission with ID works`() = runBlocking {
        val addAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}type: AccessType!, ${'$'}locationPatterns: [String!]){
                          addAccessPermission (type: ${'$'}type, locationPatterns: ${'$'}locationPatterns){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val addAccessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, addAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", addAccessPermission.getJsonArray("locationPatterns")[0])

        val accessPermissionId = addAccessPermission.getString("id")
        val getAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query (${'$'}id: String!){
                          getAccessPermission (id: ${'$'}id){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", accessPermissionId))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getAccessPermissionResp.getJsonArray("errors"))
        val accessPermission2: JsonObject = getAccessPermissionResp.getJsonObject("data")
            .getJsonObject("getAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, accessPermission2.getString("type"))
        Assertions.assertEquals("some-pattern", accessPermission2.getJsonArray("locationPatterns")[0])
    }


    @Test
    fun `ensure getRoleAccessPermissions works`() = runBlocking {
        val addRoleResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!){
                          addRole (role: ${'$'}role)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))
        val addAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}type: AccessType!, ${'$'}locationPatterns: [String!]){
                          addAccessPermission (type: ${'$'}type, locationPatterns: ${'$'}locationPatterns){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val addAccessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = addAccessPermission.getString("id")

        val addRoleAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!, ${'$'}accessPermissionId: String!){
                          addRoleAccessPermission (role: ${'$'}role, accessPermissionId: ${'$'}accessPermissionId)
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("accessPermissionId", accessPermissionId).put("role", "developer-role")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleAccessPermissionResp.getJsonArray("errors"))

        val getRoleAccessPermissions = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query (${'$'}role: String!){
                          getRoleAccessPermissions (role: ${'$'}role){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getRoleAccessPermissions.getJsonArray("errors"))
        val roleAccessPermission: JsonObject = getRoleAccessPermissions.getJsonObject("data")
            .getJsonArray("getRoleAccessPermissions")[0]
        Assertions.assertEquals(AccessType.WHITE_LIST.name, roleAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", roleAccessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure getDeveloperAccessPermissions works`() = runBlocking {
        val addDeveloperResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!){
                          addDeveloper (id: ${'$'}id){
                                id
                                accessToken
                            }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "developer-id"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!){
                          addRole (role: ${'$'}role)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}role: String!){
                          addDeveloperRole (id: ${'$'}id, role: ${'$'}role)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "developer-id").put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val addAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}type: AccessType!, ${'$'}locationPatterns: [String!]){
                          addAccessPermission (type: ${'$'}type, locationPatterns: ${'$'}locationPatterns){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val addAccessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = addAccessPermission.getString("id")

        val addRoleAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!, ${'$'}accessPermissionId: String!){
                          addRoleAccessPermission (role: ${'$'}role, accessPermissionId: ${'$'}accessPermissionId)
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("accessPermissionId", accessPermissionId).put("role", "developer-role")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleAccessPermissionResp.getJsonArray("errors"))

        val getDeveloperAccessPermissions = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query (${'$'}developerId: String!){
                          getDeveloperAccessPermissions (developerId: ${'$'}developerId){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("developerId", "developer-id"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getDeveloperAccessPermissions.getJsonArray("errors"))
        println(getDeveloperAccessPermissions)
        val developerAccessPermission: JsonObject = getDeveloperAccessPermissions.getJsonObject("data")
            .getJsonArray("getDeveloperAccessPermissions")[0]
        Assertions.assertEquals(AccessType.WHITE_LIST.name, developerAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", developerAccessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure removeAccessPermission works`() = runBlocking {
        val addAccessPermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}type: AccessType!, ${'$'}locationPatterns: [String!]){
                          addAccessPermission (type: ${'$'}type, locationPatterns: ${'$'}locationPatterns){
                                id
                                locationPatterns
                                type
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("type", AccessType.WHITE_LIST).put("locationPatterns", "some-pattern")
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val addAccessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, addAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", addAccessPermission.getJsonArray("locationPatterns")[0])

        val accessPermissionId = addAccessPermission.getString("id")
        val removeAccessPermission = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!){
                          removeAccessPermission (id: ${'$'}id)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", accessPermissionId))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeAccessPermission.getJsonArray("errors"))
        Assertions.assertTrue(removeAccessPermission.getJsonObject("data").getBoolean("removeAccessPermission"))
    }

    @Test
    fun `ensure all role permissions are known`() = runBlocking {
        val knownRolePermissions = liveManagementService.getRolePermissions(ROLE_MANAGER.roleName).await()
        RolePermission.values().forEach {
            assert(knownRolePermissions.contains(it)) {
                "Role permission $it is not known"
            }
        }

        val getRolePermissionsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query (${'$'}role: String!){
                          getRolePermissions (role: ${'$'}role)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("role", ROLE_MANAGER.roleName))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getRolePermissionsResp.getJsonArray("errors"))
        val getRolePermissions = getRolePermissionsResp.getJsonObject("data").getJsonArray("getRolePermissions")
        RolePermission.values().forEach {
            assert(getRolePermissions.contains(it.name)) {
                "Role permission $it is not known"
            }
        }
    }

    @Test
    fun `ensure default-test client accessor is present`() = runBlocking {
        val testClientAccessList = liveManagementService.getClientAccessors().await()
        Assertions.assertNotNull(testClientAccessList.find { it.id == "test-id" })
        Assertions.assertNotNull(testClientAccessList.find { it.secret == "test-secret" })

        val getClientAccessorsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """ query {
                          getClientAccessors {
                            id,
                            secret
                          }
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getClientAccessorsResp.getJsonArray("errors"))
        val testClientAccessJsonArray = getClientAccessorsResp.getJsonObject("data").getJsonArray("getClientAccessors")
        Assertions.assertEquals(testClientAccessJsonArray.getJsonObject(0).getString("id"), "test-id")
        Assertions.assertEquals(testClientAccessJsonArray.getJsonObject(0).getString("secret"), "test-secret")
    }

    @Test
    fun `ensure getting client accessor works`() = runBlocking {
        val clientAccess = liveManagementService.getClientAccess("test-id").await()
        Assertions.assertEquals("test-id", clientAccess?.id)
        Assertions.assertEquals("test-secret", clientAccess?.secret)

        //todo: No GraphQL for this endpoint
//        val getClientAccessResp = request
//            .sendJsonObject(
//                JsonObject().put(
//                    "query",
//                    """query (${'$'}id: String!){
//                          getClientAccess (id: ${'$'}id){
//                            id,
//                            secret
//                          }
//                        }
//                    """.trimIndent()
//                ).put("variables", JsonObject().put("id", "test-id"))
//            ).await().bodyAsJsonObject()
//        assertNull(getClientAccessResp.getJsonArray("errors"))
//        val clientAccessJsonObject = getClientAccessResp.getJsonObject("data").getJsonObject("getClientAccessors")
//        Assertions.assertEquals(clientAccessJsonObject.getString("id"), "test-id")
//        Assertions.assertEquals(clientAccessJsonObject.getString("secret"), "test-secret")
    }

    @Test
    fun `ensure adding new client accessor works`() = runBlocking {
        //todo: does not accept custom id nor secret
        val generatedClientAccess = liveManagementService.addClientAccess().await()
        Assertions.assertNotNull(generatedClientAccess.id)
        Assertions.assertNotNull(generatedClientAccess.secret)

        val addClientAccessResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation {
                          addClientAccess {
                            id,
                            secret
                          }
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addClientAccessResp.getJsonArray("errors"))
        val generatedClientAccessGql = addClientAccessResp.getJsonObject("data").getJsonObject("addClientAccess")
        Assertions.assertNotNull(generatedClientAccessGql.getString("id"))
        Assertions.assertNotNull(generatedClientAccessGql.getString("secret"))
    }

    @Test
    fun `ensure remove client access works`() = runBlocking {
        val clientAccessList = liveManagementService.getClientAccessors().await()
        Assertions.assertNotNull(clientAccessList.find { it.id == "test-id" })

        Assertions.assertTrue(liveManagementService.removeClientAccess("test-id").await())

        val removedClientAccessList = liveManagementService.getClientAccessors().await()
        Assertions.assertNull(removedClientAccessList.find { it.id == "test-id" })

        val clientId = liveManagementService.addClientAccess().await().id
        val addedClientAccess = liveManagementService.getClientAccessors().await()
        Assertions.assertNotNull(addedClientAccess.find { it.id == clientId })

        val removeClientAccessResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """ mutation (${'$'}id: String!){
                          removeClientAccess (id: ${'$'}id)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", clientId))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeClientAccessResp.getJsonArray("errors"))

        Assertions.assertTrue(removeClientAccessResp.getJsonObject("data").getBoolean("removeClientAccess"))

        val removedClientAccessListGql = liveManagementService.getClientAccessors().await()
        Assertions.assertNull(removedClientAccessListGql.find { it.id == clientId })
    }

    @Test
    fun `ensure update client access works`() = runBlocking {
        val clientAccess = liveManagementService.addClientAccess().await()
        val clientId = clientAccess.id
        val clientSecret = clientAccess.secret

        val updatedClientAccess = liveManagementService.updateClientAccess(clientId).await()
        Assertions.assertEquals(clientId, updatedClientAccess.id)
        Assertions.assertNotEquals(clientSecret, updatedClientAccess.secret)

        val updateClientAccessResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """ mutation (${'$'}id: String!){
                          updateClientAccess (id: ${'$'}id){
                            id,
                            secret
                          }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", clientId))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(updateClientAccessResp.getJsonArray("errors"))

        val updatedClientAccessJsonObject =
            updateClientAccessResp.getJsonObject("data").getJsonObject("updateClientAccess")
        Assertions.assertEquals(clientId, updatedClientAccessJsonObject.getString("id"))
        Assertions.assertNotEquals(clientSecret, updatedClientAccessJsonObject.getString("secret"))

    }
}
