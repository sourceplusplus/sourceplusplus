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
import java.io.File
import java.util.*

class GetFileEndpointsIT : PlatformIntegrationTest() {

    @Test
    fun `java vertx endpoints`(): Unit = runBlocking {
        doTest("java")
    }

    @Test
    fun `kotlin vertx endpoints`(): Unit = runBlocking {
        doTest("kotlin")
    }

    private suspend fun doTest(lang: String) {
        val workspaceId = UUID.randomUUID().toString()
        insightService.createWorkspace(workspaceId).await()
        log.info("Workspace ID: $workspaceId")
        val fileExtension = if (lang == "kotlin") "kt" else lang
        val className = "${lang.capitalize()}VertxEndpoints"
        val sourceFile = File("src/test/$lang/application/$className.$fileExtension")
        insightService.uploadSourceCode(
            workspaceId,
            JsonObject()
                .put("file_path", sourceFile.absolutePath)
                .put(
                    "file_content", vertx.fileSystem().readFile(
                        sourceFile.absolutePath
                    ).await()
                )
        ).await()

        val projectEndpoints = insightService.getProjectEndpoints(workspaceId, 0, 10).await()
        assertEquals(2, projectEndpoints.size())
        assertTrue(projectEndpoints.any {
            JsonObject.mapFrom(it) == JsonObject()
                .put("uri", "POST:/debug/login-error/login")
                .put(
                    "qualifiedName",
                    "application.$className.login(io.vertx.ext.web.RoutingContext)"
                )
        })
        assertTrue(projectEndpoints.any {
            JsonObject.mapFrom(it) == JsonObject()
                .put("uri", "POST:/debug/login-error/create-user")
                .put(
                    "qualifiedName",
                    "application.$className.createUser(io.vertx.ext.web.RoutingContext)"
                )
        })
    }
}
