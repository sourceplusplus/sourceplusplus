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
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.protocol.platform.auth.DeveloperRole.Companion.ROLE_MANAGER
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
                    """
                        mutation {
                          reset
                        }
                    """.trimIndent()
                )
            ).await().bodyAsJsonObject()
        Assertions.assertTrue(resetResp.getJsonObject("data").getBoolean("reset"))
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
                    """
                        query (${'$'}role: String!){
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
                    """
                        query {
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
//                    """
//                        query (${'$'}id: String!){
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
                    """
                        mutation {
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
                    """
                        mutation (${'$'}id: String!){
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
                    """
                        mutation (${'$'}id: String!){
                          updateClientAccess (id: ${'$'}id){
                            id,
                            secret
                          }
                        }
                    """.trimIndent()
                ).put("variables", JsonObject().put("id", clientId))
            ).await().bodyAsJsonObject()
        Assertions.assertNull(updateClientAccessResp.getJsonArray("errors"))

        val updatedClientAccessJsonObject = updateClientAccessResp.getJsonObject("data").getJsonObject("updateClientAccess")
        Assertions.assertEquals(clientId, updatedClientAccessJsonObject.getString("id"))
        Assertions.assertNotEquals(clientSecret, updatedClientAccessJsonObject.getString("secret"))

    }
}
