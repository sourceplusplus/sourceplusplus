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
