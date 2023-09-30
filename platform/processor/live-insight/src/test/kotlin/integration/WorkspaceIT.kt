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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class WorkspaceIT : PlatformIntegrationTest() {

    @Test
    fun `upload missing repo`(): Unit = runBlocking {
        val workspaceId = UUID.randomUUID().toString()
        insightService.createWorkspace(workspaceId).await()
        log.info("Workspace ID: $workspaceId")
        try {
            insightService.uploadRepository(
                workspaceId,
                JsonObject()
                    .put("repo_url", "https://github.com/bfergerson/doesntexist")
                    .put("repo_branch", "master")
            ).await()
        } catch (e: Exception) {
            log.info("Exception: $e")
            assertTrue(e.message!!.contains("Failed to clone repository"))
        }
    }
}
