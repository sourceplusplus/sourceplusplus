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

import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class GetProjectEndpointsIT : PlatformIntegrationTest() {

    @Test
    fun testGitUpload(): Unit = runBlocking {
        //upload git
        val workspaceId = UUID.randomUUID().toString()
        log.info("Workspace ID: $workspaceId")
        insightService.uploadRepository(
            workspaceId,
            JsonObject()
                .put("repo_url", "https://github.com/IntelliDebug/java-login-bug")
                .put("repo_branch", "master")
        ).await()

        val projectEndpoints = insightService.getProjectEndpoints(workspaceId, 0, 10).await()
        assertEquals(2, projectEndpoints.size())
        assertTrue(projectEndpoints.any {
            JsonObject.mapFrom(it) == JsonObject()
                .put("uri", "POST:/debug/login-error/login")
                .put(
                    "qualifiedName",
                    "id.demo.LoginError.login(java.lang.String,java.lang.String)"
                )
        })
        assertTrue(projectEndpoints.any {
            JsonObject.mapFrom(it) == JsonObject()
                .put("uri", "POST:/debug/login-error/create-user")
                .put(
                    "qualifiedName",
                    "id.demo.LoginError.createUser(java.lang.String,java.lang.String,java.lang.String)"
                )
        })
    }
}
