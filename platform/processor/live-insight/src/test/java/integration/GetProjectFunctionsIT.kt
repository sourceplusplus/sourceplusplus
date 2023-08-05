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
        val sourceFile = File("src/test/java/integration/FunctionDurationTest.java")
        insightService.uploadSourceCode(
            workspaceId,
            JsonObject()
                .put("file_path", sourceFile.absolutePath)
                .put(
                    "file_content", vertx.fileSystem().readFile(
                        sourceFile.absolutePath
                    ).toCompletionStage().toCompletableFuture().get()
                )
        ).toCompletionStage().toCompletableFuture().get()

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
        insightService.uploadRepository(
            workspaceId,
            JsonObject()
                .put("repo_url", "https://github.com/IntelliDebug/java-login-bug")
                .put("repo_branch", "master")
        ).toCompletionStage().toCompletableFuture().get()

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
