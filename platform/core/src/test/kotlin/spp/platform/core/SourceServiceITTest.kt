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
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
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
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))

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
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, accessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", accessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure addDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val dataRedaction = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", dataRedaction.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, dataRedaction.getString("type"))
        Assertions.assertEquals("some-lookup", dataRedaction.getString("lookup"))
        Assertions.assertEquals("some-replacement", dataRedaction.getString("replacement"))
    }

    @Test
    fun `ensure updateDataRedaction works`() = runBlocking {
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val dataRedaction = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", dataRedaction.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, dataRedaction.getString("type"))

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

    //todo: fails with Exception in thread "vert.x-eventloop-thread-1" java.lang.NullPointerException
    //	at spp.platform.storage.RedisStorage.getDataRedaction$suspendImpl(RedisStorage.kt:203)
//    @Test
//    fun `ensure getDataRedaction with ID works`() = runBlocking {
//    val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
//        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
//        val dataRedaction = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
//        Assertions.assertEquals("some-id", dataRedaction.getString("id"))
//        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, dataRedaction.getString("type"))
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
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val dataRedaction = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", dataRedaction.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, dataRedaction.getString("type"))

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
        val addDataRedactionResp = request.sendJsonObject(addDataRedactionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDataRedactionResp.getJsonArray("errors"))
        val dataRedaction = addDataRedactionResp.getJsonObject("data").getJsonObject("addDataRedaction")
        Assertions.assertEquals("some-id", dataRedaction.getString("id"))
        Assertions.assertEquals(RedactionType.VALUE_REGEX.name, dataRedaction.getString("type"))

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
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, accessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", accessPermission.getJsonArray("locationPatterns")[0])

        val accessPermissionId = accessPermission.getString("id")
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
    fun `ensure addDeveloper works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))
        val developer: JsonObject = addDeveloperResp.getJsonObject("data").getJsonObject("addDeveloper")
        Assertions.assertEquals("developer-id", developer.getString("id"))
        Assertions.assertNotNull(developer.getString("accessToken"))
    }


    @Test
    fun `ensure getDevelopers works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val getDevelopersResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query {
                          getDevelopers {
                                id
                                accessToken
                            }
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getDevelopersResp.getJsonArray("errors"))
        val developers = getDevelopersResp.getJsonObject("data").getJsonArray("getDevelopers")
        Assertions.assertTrue(developers.any {
            it as JsonObject
            it.getString("id").equals("developer-id")
        })
        Assertions.assertTrue(developers.any {
            it as JsonObject
            it.getString("accessToken") == null
        })
    }

    @Test
    fun `ensure removeDeveloper works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val removeDeveloperResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!){
                          removeDeveloper (id: ${'$'}id)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "developer-id"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeDeveloperResp.getJsonArray("errors"))
        Assertions.assertTrue(removeDeveloperResp.getJsonObject("data").getBoolean("removeDeveloper"))
    }

    @Test
    fun `ensure addRoleResp works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))
        Assertions.assertTrue(addRoleResp.getJsonObject("data").getBoolean("addRole"))
    }

    @Test
    fun `ensure getRoles works`() = runBlocking {
        val getRolesResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query {
                          getRoles {
                                roleName    
                            }
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getRolesResp.getJsonArray("errors"))
        val roles = getRolesResp.getJsonObject("data").getJsonArray("getRoles")
        Assertions.assertTrue(roles.size() > 0)
    }

//    @Test
//    fun `ensure getRoleDataRedactions works`() = runBlocking {
//        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
//        Assertions.assertNull(addRoleResp.getJsonArray("errors"))
//
//        val getRolesResp = request
//            .sendJsonObject(
//                JsonObject().put(
//                    "query",
//                    """query (${'$'}role: String!){
//                          getRoleDataRedactions (role: ${'$'}role){
//                                 id
//                                type
//                                lookup
//                                replacement
//                            }
//                        }
//                    """.trimIndent()
//                ).put("variables", JsonObject().put("role", "developer-role"))
//            ).await().bodyAsJsonObject()
//        Assertions.assertNull(getRolesResp.getJsonArray("errors"))
//        val roleDataRedactions = getRolesResp.getJsonObject("data").getJsonArray("getRoleDataRedactions")
//        Assertions.assertTrue(roleDataRedactions.size() > 0)
//    }

    @Test
    fun `ensure removeRole works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val removeRoleResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!){
                          removeRole (role: ${'$'}role)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeRoleResp.getJsonArray("errors"))
        Assertions.assertTrue(removeRoleResp.getJsonObject("data").getBoolean("removeRole"))
    }

    @Test
    fun `ensure addDeveloperRole works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperRoleResp.getJsonArray("errors"))
        Assertions.assertTrue(addDeveloperRoleResp.getJsonObject("data").getBoolean("addDeveloperRole"))
    }

    @Test
    fun `ensure getDeveloperRoles works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val getDeveloperRolesResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """query (${'$'}id: String!){
                          getDeveloperRoles (id: ${'$'}id){
                                roleName    
                            }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "developer-id"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(getDeveloperRolesResp.getJsonArray("errors"))
    }

    @Test
    fun `ensure removeDeveloperRole works`() = runBlocking {
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val removeDeveloperRoleResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}id: String!, ${'$'}role: String!){
                          removeDeveloperRole (id: ${'$'}id, role: ${'$'}role)
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", "developer-id").put("role", "developer-role"))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeDeveloperRoleResp.getJsonArray("errors"))
        Assertions.assertTrue(removeDeveloperRoleResp.getJsonObject("data").getBoolean("removeDeveloperRole"))
    }

    @Test
    fun `ensure getRoleAccessPermissions works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = accessPermission.getString("id")

        val addRoleAccessPermissionResp =
            request.sendJsonObject(addRoleAccessPermissionRequest(accessPermissionId)).await().bodyAsJsonObject()
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
        val addDeveloperResp = request.sendJsonObject(addDeveloperRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperResp.getJsonArray("errors"))

        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addDeveloperRoleResp = request.sendJsonObject(addDeveloperRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addDeveloperRoleResp.getJsonArray("errors"))

        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        val accessPermissionId = accessPermission.getString("id")

        val addRoleAccessPermissionResp =
            request.sendJsonObject(addRoleAccessPermissionRequest(accessPermissionId)).await().bodyAsJsonObject()
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
        val developerAccessPermission: JsonObject = getDeveloperAccessPermissions.getJsonObject("data")
            .getJsonArray("getDeveloperAccessPermissions")[0]
        Assertions.assertEquals(AccessType.WHITE_LIST.name, developerAccessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", developerAccessPermission.getJsonArray("locationPatterns")[0])
    }

    @Test
    fun `ensure removeAccessPermission works`() = runBlocking {
        val addAccessPermissionResp = request.sendJsonObject(addAccessPermissionRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addAccessPermissionResp.getJsonArray("errors"))
        val accessPermission = addAccessPermissionResp.getJsonObject("data").getJsonObject("addAccessPermission")
        Assertions.assertEquals(AccessType.WHITE_LIST.name, accessPermission.getString("type"))
        Assertions.assertEquals("some-pattern", accessPermission.getJsonArray("locationPatterns")[0])

        val accessPermissionId = accessPermission.getString("id")
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
    fun `ensure addRolePermission works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addRolePermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!, ${'$'}permission: String!){
                          addRolePermission (role: ${'$'}role, permission: ${'$'}permission)
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("role", "developer-role").put("permission", RolePermission.ADD_ROLE)
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addRolePermissionResp.getJsonArray("errors"))
        Assertions.assertTrue(addRolePermissionResp.getJsonObject("data").getBoolean("addRolePermission"))
    }

    @Test
    fun `ensure addLiveViewSubscription works`() = runBlocking {
        val addLiveViewSubscriptionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}input: LiveViewSubscriptionInput!){
                          addLiveViewSubscription (input: ${'$'}input){
                                subscriptionId
                                entityIds
                                artifactQualifiedName {
                                    identifier
                                    commitId
                                    artifactType
                                    lineNumber
                                    operationName
                                }
                                artifactLocation {
                                    source
                                    line
                                }
                                liveViewConfig {
                                    viewName
                                    viewMetrics
                                    refreshRateLimit
                                }
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("input", mapOf("entityIds" to listOf(1,222,3)))

                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addLiveViewSubscriptionResp.getJsonArray("errors"))
        val liveViewSubscription = addLiveViewSubscriptionResp.getJsonObject("data").getJsonObject("addLiveViewSubscription")
        Assertions.assertNotNull(liveViewSubscription.getString("subscriptionId"))
        val entityIds = liveViewSubscription.getJsonArray("entityIds")
        Assertions.assertTrue(entityIds.any {
            it as String
            it == "222"
        })
    }

    @Test
    fun `ensure addLiveBreakpoint works`() = runBlocking {
        val addLiveBreakpointResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}input: LiveBreakpointInput!){
                          addLiveBreakpoint (input: ${'$'}input){
                                id
                                location {
                                    source
                                    line
                                }
                                condition
                                expiresAt
                                hitLimit
                                throttle {
                                    limit
                                    step
                                }
                                meta {
                                    name
                                    value
                                }
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("input", mapOf("location" to mapOf("source" to "doing", "line" to 17)))
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addLiveBreakpointResp.getJsonArray("errors"))
        val liveBreakpoint = addLiveBreakpointResp.getJsonObject("data").getJsonObject("addLiveBreakpoint")
        val liveBreakpointLocation = liveBreakpoint.getJsonObject("location")
        Assertions.assertEquals("doing", liveBreakpointLocation.getString("source"))
        Assertions.assertEquals(17, liveBreakpointLocation.getInteger("line"))
    }

    @Test
    fun `ensure addLiveLog works`() = runBlocking {
        val addLiveLogResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}input: LiveLogInput!){
                          addLiveLog (input: ${'$'}input){
                                id
                                location {
                                    source
                                    line
                                }
                                condition
                                expiresAt
                                hitLimit
                                throttle {
                                    limit
                                    step
                                }
                                meta {
                                    name
                                    value
                                }
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put(
                        "input",
                        mapOf("location" to mapOf("source" to "doing", "line" to 19), "logFormat" to "formatting")
                    )
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addLiveLogResp.getJsonArray("errors"))
        val liveLog = addLiveLogResp.getJsonObject("data").getJsonObject("addLiveLog")
        val liveLogLocation = liveLog.getJsonObject("location")
        Assertions.assertEquals("doing", liveLogLocation.getString("source"))
        Assertions.assertEquals(19, liveLogLocation.getInteger("line"))
    }

    @Test
    fun `ensure addLiveSpan works`() = runBlocking {
        val addLiveSpanResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}input: LiveSpanInput!){
                          addLiveSpan (input: ${'$'}input){
                                id
                                location {
                                    source
                                    line
                                }
                                condition
                                expiresAt
                                hitLimit
                                throttle {
                                    limit
                                    step
                                }
                                meta {
                                    name
                                    value
                                }
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("input",
                        mapOf("location" to mapOf("source" to "doing"), "operationName" to "name-of-operation"))
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addLiveSpanResp.getJsonArray("errors"))
        val liveSpan = addLiveSpanResp.getJsonObject("data").getJsonObject("addLiveSpan")
        val liveSpanLocation = liveSpan.getJsonObject("location")
        Assertions.assertEquals("doing", liveSpanLocation.getString("source"))
    }

    @Test
    fun `ensure addLiveMeter works`() = runBlocking {
        val addLiveMeterResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}input: LiveMeterInput!){
                          addLiveMeter (input: ${'$'}input){
                                id
                                location {
                                    source
                                    line
                                }
                                condition
                                expiresAt
                                hitLimit
                                throttle {
                                    limit
                                    step
                                }
                                meta {
                                    name
                                    value
                                }
                            }
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put(
                        "input",
                        mapOf("location" to mapOf("source" to "doing", "line" to 19), "meterName" to "name-of-meter",
                        "meterType" to MeterType.COUNT, "metricValue" to MetricValue(MetricValueType.NUMBER, "3")
                        )
                    )
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addLiveMeterResp.getJsonArray("errors"))
        val liveMeter = addLiveMeterResp.getJsonObject("data").getJsonObject("addLiveMeter")
        val liveMeterLocation = liveMeter.getJsonObject("location")
        Assertions.assertEquals("doing", liveMeterLocation.getString("source"))
        Assertions.assertEquals(19, liveMeterLocation.getInteger("line"))
    }

    @Test
    fun `ensure clearLiveViewSubscriptions works`() = runBlocking {
        val clearLiveViewSubscriptionsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation {
                          clearLiveViewSubscriptions
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(clearLiveViewSubscriptionsResp.getJsonArray("errors"))
        Assertions.assertTrue(
            clearLiveViewSubscriptionsResp.getJsonObject("data").getBoolean("clearLiveViewSubscriptions")
        )
    }

    //todo: does not validate if LiveInstrument with ID exist
    //return null
//    @Test
//    fun `ensure removeLiveInstrument works`() = runBlocking {
//        val removeLiveInstrumentResp = request
//            .sendJsonObject(
//                JsonObject().put(
//                    "query",
//                    """mutation (${'$'}id: String!){
//                          removeLiveInstrument (id: ${'$'}id){
//                                id
//                                location {
//                                    source
//                                    line
//                                }
//                            }
//                        }
//                    """.trimIndent()
//                ).put("variables", JsonObject().put("id", "developer-role"))
//            ).await().bodyAsJsonObject()
//        Assertions.assertNull(removeLiveInstrumentResp.getJsonArray("errors"))
//        Assertions.assertNotNull(removeLiveInstrumentResp.getJsonObject("data").getValue("removeLiveInstrument"))
//    }
//
    //todo: not sure what to use as verification
//    @Test
//    fun `ensure removeLiveInstruments works`() = runBlocking {
//
//        val removeLiveInstrumentsResp = request
//            .sendJsonObject(
//                JsonObject().put(
//                    "query",
//                    """mutation (${'$'}source: String!, ${'$'}line: Int!){
//                          removeLiveInstruments (source: ${'$'}source, line: ${'$'}line){
//                                id
//                                location {
//                                    source
//                                    line
//                                }
//                            }
//                        }
//                    """.trimIndent()
//                ).put("variables", JsonObject().put("source", "developer-role").put("line", 17))
//            ).await().bodyAsJsonObject()
//        Assertions.assertNull(removeLiveInstrumentsResp.getJsonArray("errors"))
//        val liveInstruments = removeLiveInstrumentsResp.getJsonObject("data").getJsonArray("removeLiveInstruments")
//        println(liveInstruments)
//        Assertions.assertNotNull(liveInstruments)
////        Assertions.assertTrue(liveInstruments.size()>0)
//    }

    @Test
    fun `ensure clearLiveInstruments works`() = runBlocking {
        val clearLiveInstrumentsResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation {
                          clearLiveInstruments
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(clearLiveInstrumentsResp.getJsonArray("errors"))
        Assertions.assertNotNull(clearLiveInstrumentsResp.getJsonObject("data").getBoolean("clearLiveInstruments"))
    }

    @Test
    fun `ensure removeRolePermission works`() = runBlocking {
        val addRoleResp = request.sendJsonObject(addRoleRequest).await().bodyAsJsonObject()
        Assertions.assertNull(addRoleResp.getJsonArray("errors"))

        val addRolePermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!, ${'$'}permission: String!){
                          addRolePermission (role: ${'$'}role, permission: ${'$'}permission)
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("role", "developer-role").put("permission", RolePermission.ADD_ROLE)
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(addRolePermissionResp.getJsonArray("errors"))
        Assertions.assertTrue(addRolePermissionResp.getJsonObject("data").getBoolean("addRolePermission"))

        val removeRolePermissionResp = request
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    """mutation (${'$'}role: String!, ${'$'}permission: String!){
                          removeRolePermission (role: ${'$'}role, permission: ${'$'}permission)
                        }
                    """.trimIndent()
                ).put(
                    "variables",
                    JsonObject().put("role", "developer-role").put("permission", RolePermission.ADD_ROLE)
                )
            ).await().bodyAsJsonObject()
        Assertions.assertNull(removeRolePermissionResp.getJsonArray("errors"))
        Assertions.assertTrue(removeRolePermissionResp.getJsonObject("data").getBoolean("removeRolePermission"))
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
        Assertions.assertTrue(testClientAccessJsonArray.any {
            it as JsonObject
            it.getString("id").equals("test-id")
        })
        Assertions.assertTrue(testClientAccessJsonArray.any {
            it as JsonObject
            it.getString("secret").equals("test-secret")
        })
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

    private val addDataRedactionRequest: JsonObject = JsonObject().put(
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

    private val addAccessPermissionRequest: JsonObject = JsonObject().put(
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

    private val addDeveloperRequest: JsonObject = JsonObject().put(
        "query",
        """mutation (${'$'}id: String!){
                          addDeveloper (id: ${'$'}id){
                                id
                                accessToken
                            }
                        }
                    """.trimIndent()
    ).put("variables", JsonObject().put("id", "developer-id"))

    private val addDeveloperRoleRequest: JsonObject = JsonObject().put(
        "query",
        """mutation (${'$'}id: String!, ${'$'}role: String!){
                          addDeveloperRole (id: ${'$'}id, role: ${'$'}role)
                        }
                    """.trimIndent()
    ).put("variables", JsonObject().put("id", "developer-id").put("role", "developer-role"))

    private val addRoleRequest: JsonObject = JsonObject().put(
        "query",
        """mutation (${'$'}role: String!){
                          addRole (role: ${'$'}role)
                        }
                    """.trimIndent()
    ).put("variables", JsonObject().put("role", "developer-role"))

    private fun addRoleAccessPermissionRequest(accessPermissionId: String): JsonObject {
        return JsonObject().put(
            "query",
            """mutation (${'$'}role: String!, ${'$'}accessPermissionId: String!){
                          addRoleAccessPermission (role: ${'$'}role, accessPermissionId: ${'$'}accessPermissionId)
                        }
                    """.trimIndent()
        ).put(
            "variables",
            JsonObject().put("accessPermissionId", accessPermissionId).put("role", "developer-role")
        )
    }
}
