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
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*

class GetProjectFunctionsIT : PlatformIntegrationTest() {

    @Test
    fun testSourceCodeUpload(): Unit = runBlocking {
        val testContext = VertxTestContext()

        //upload source code
        val workspaceId = UUID.randomUUID().toString()
        log.info("Workspace ID: $workspaceId")
        val sourceFile = File("src/test/java/integration/FunctionDurationTest.java")
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

        val projectFunctions = insightService.getProjectFunctions(workspaceId, 0, 10).await()
        testContext.verify {
            assertEquals(4, projectFunctions.size())
            assertTrue(projectFunctions.any { it == "integration.FunctionDurationTest.function1()" })
            assertTrue(projectFunctions.any { it == "integration.FunctionDurationTest.function2()" })
            assertTrue(projectFunctions.any { it == "integration.FunctionDurationTest.function3()" })
            assertTrue(projectFunctions.any { it == "integration.FunctionDurationTest.testFunctionDuration()" })
        }
    }

    @Test
    fun testGitUpload(): Unit = runBlocking {
        val testContext = VertxTestContext()

        //upload git
        val workspaceId = UUID.randomUUID().toString()
        log.info("Workspace ID: $workspaceId")
        insightService.uploadRepository(
            workspaceId,
            JsonObject()
                .put("repo_url", "https://github.com/IntelliDebug/java-login-bug")
                .put("repo_branch", "master")
        ).await()

        val projectFunctions = insightService.getProjectFunctions(workspaceId, 0, 10).await()
        testContext.verify {
            assertEquals(6, projectFunctions.size())
            assertTrue(projectFunctions.any { it == "id.demo.LoginError.login(java.lang.String,java.lang.String)" })
            assertTrue(projectFunctions.any { it == "id.demo.LoginError.createUser(java.lang.String,java.lang.String,java.lang.String)" })
            assertTrue(projectFunctions.any { it == "id.demo.LoginError\$UserStorage.getUser(java.lang.String)" })
            assertTrue(projectFunctions.any { it == "id.demo.LoginError\$UserStorage.getUserByEmail(java.lang.String)" })
            assertTrue(projectFunctions.any { it == "id.demo.LoginError\$UserStorage.createUser(java.lang.String,java.lang.String,java.lang.String)" })
            assertTrue(projectFunctions.any { it == "id.demo.Main.main(java.lang.String[])" })
        }
    }
}
