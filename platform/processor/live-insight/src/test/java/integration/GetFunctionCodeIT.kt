package integration

import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import java.util.*

class GetFunctionCodeIT : PlatformIntegrationTest() {

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

        val functionCode = insightService.getFunctionCode(
            workspaceId,
            ArtifactQualifiedName(
                identifier = "id.demo.LoginError.login(java.lang.String,java.lang.String)",
                type = ArtifactType.FUNCTION
            )
        ).await().getString("code")
        testContext.verify {
            assertTrue(functionCode?.contains("login(@Nullable String username, @Nullable String password)") == true)
        }
    }
}
