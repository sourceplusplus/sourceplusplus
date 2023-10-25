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
import org.junit.jupiter.api.Test
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import java.io.File
import java.util.*

class GetFunctionCodeIT : PlatformIntegrationTest() {

    @Test
    fun getFunctionCode(): Unit = runBlocking {
        //upload source code
        val workspaceId = UUID.randomUUID().toString()
        insightService.createWorkspace(workspaceId).await()
        log.info("Workspace ID: $workspaceId")
        val sourceFile = File("src/test/kotlin/application/KotlinVertxEndpoints.kt")
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

        val functionCode1 = insightService.getFunctionCode(
            workspaceId,
            ArtifactQualifiedName(
                identifier = "application.KotlinVertxEndpoints.login(io.vertx.ext.web.RoutingContext)",
                type = ArtifactType.FUNCTION
            )
        ).await().getString("code")
        assertEquals("private fun login(ctx: RoutingContext) = Unit", functionCode1.trim())

        val functionCode2 = insightService.getFunctionCode(
            workspaceId,
            ArtifactQualifiedName(
                identifier = "application.KotlinVertxEndpoints.login",
                type = ArtifactType.FUNCTION
            )
        ).await().getString("code")
        assertEquals("private fun login(ctx: RoutingContext) = Unit", functionCode2.trim())
    }
}
