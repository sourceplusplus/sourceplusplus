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
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spp.protocol.platform.auth.DeveloperRole.Companion.ROLE_MANAGER
import spp.protocol.platform.auth.RolePermission

class SourceServiceITTest : PlatformIntegrationTest() {

    @Test
    fun `ensure all role permissions are known`() = runBlocking {
        val knownRolePermissions = liveManagementService.getRolePermissions(ROLE_MANAGER.roleName).await()
        RolePermission.values().forEach {
            assert(knownRolePermissions.contains(it)) {
                "Role permission $it is not known"
            }
        }

        val platformHost = System.getenv("SPP_PLATFORM_HOST") ?: "localhost"
        val client = WebClient.create(
            vertx,
            WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false)
        )
        val getRolePermissionsResp = client.post(12800, platformHost, "/graphql/spp")
            .bearerTokenAuthentication(SYSTEM_JWT_TOKEN)
            .sendJsonObject(
                JsonObject().put(
                    "query",
                    "query (\$role: String!) {\n" +
                            "  getRolePermissions(role: \$role)\n" +
                            "}\n"
                ).put("variables", JsonObject().put("role", ROLE_MANAGER.roleName))
            ).await().bodyAsJsonObject()
        assertNull(getRolePermissionsResp.getJsonArray("errors"))
        val getRolePermissions = getRolePermissionsResp.getJsonObject("data").getJsonArray("getRolePermissions")
        RolePermission.values().forEach {
            assert(getRolePermissions.contains(it.name)) {
                "Role permission $it is not known"
            }
        }
    }
}
