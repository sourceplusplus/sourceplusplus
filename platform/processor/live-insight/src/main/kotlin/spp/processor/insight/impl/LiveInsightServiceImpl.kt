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
package spp.processor.insight.impl

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream
import spp.jetbrains.artifact.service.toArtifact
import spp.jetbrains.marker.service.getFullyQualifiedName
import spp.processor.insight.InsightProcessor
import spp.processor.insight.provider.InsightWorkspaceProvider
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.insight.InsightType
import spp.protocol.service.LiveInsightService
import java.io.File

class LiveInsightServiceImpl : CoroutineVerticle(), LiveInsightService {

    private val log = KotlinLogging.logger {}

    override fun uploadSourceCode(workspaceId: String, sourceCode: JsonObject): Future<Void> {
        val tempDir = File("/tmp/$workspaceId").apply { mkdirs() }
        val filename = File(sourceCode.getString("file_path")).name
        log.info("Uploading {} to workspace {}", filename, workspaceId)

        val sourceFile = File(tempDir.absolutePath, filename)
        sourceFile.createNewFile()
        vertx.fileSystem().writeFileBlocking(sourceFile.absolutePath, sourceCode.getBuffer("file_content"))
        InsightWorkspaceProvider.createWorkspace(workspaceId).addSourceDirectory(sourceFile)

        log.info("Uploaded {} to workspace {}", filename, workspaceId)
        return Future.succeededFuture()
    }

    override fun uploadRepository(workspaceId: String, repository: JsonObject): Future<Void> {
        log.info("Uploading repository {} to workspace {}", repository, workspaceId)
        val tempDir = File("/tmp/$workspaceId").apply { mkdirs() }
        val repoUrl = repository.getString("repo_url")
        val repoBranch = repository.getString("repo_branch")
        val repoPath = repository.getString("repo_path")
        val srcPath = repository.getString("src_path") ?: "src/main/java"

        val promise = Promise.promise<Void>()
        launch(vertx.dispatcher()) {
            val process = vertx.executeBlocking {
                val process = if (repoPath.isNullOrEmpty()) {
                    ProcessExecutor()
                        .command("git", "clone", "-b", repoBranch, repoUrl, tempDir.absolutePath)
                        .redirectOutput(Slf4jStream.of(log).asInfo())
                        .redirectError(Slf4jStream.of(log).asError())
                        .execute()
                } else {
                    //sparse checkout
                    ProcessExecutor()
                        .command("git", "init")
                        .directory(tempDir)
                        .redirectOutput(Slf4jStream.of(log).asInfo())
                        .redirectError(Slf4jStream.of(log).asError())
                        .execute()

                    ProcessExecutor()
                        .command("git", "remote", "add", "-f", "origin", repoUrl)
                        .directory(tempDir)
                        .redirectOutput(Slf4jStream.of(log).asInfo())
                        .redirectError(Slf4jStream.of(log).asError())
                        .execute()

                    ProcessExecutor()
                        .command("git", "config", "core.sparsecheckout", "true")
                        .directory(tempDir)
                        .redirectOutput(Slf4jStream.of(log).asInfo())
                        .redirectError(Slf4jStream.of(log).asError())
                        .execute()

                    ProcessExecutor()
                        .command("git", "sparse-checkout", "set", repoPath)
                        .directory(tempDir)
                        .redirectOutput(Slf4jStream.of(log).asInfo())
                        .redirectError(Slf4jStream.of(log).asError())
                        .execute()

                    ProcessExecutor()
                        .command("git", "pull", "origin", repoBranch)
                        .directory(tempDir)
                        .redirectOutput(Slf4jStream.of(log).asInfo())
                        .redirectError(Slf4jStream.of(log).asError())
                        .execute()
                }
                it.complete(process)
            }.await()

            InsightWorkspaceProvider.createWorkspace(workspaceId)
                .addSourceDirectory(File(tempDir.absolutePath, srcPath))

            if (process.exitValue != 0) {
                log.error("Failed to clone repository: {}", repoUrl)
                promise.fail("Failed to clone repository: $repoUrl")
            } else {
                log.info("Cloned repository {} to workspace {}", repoUrl, workspaceId)
                promise.complete()
            }
        }

        return promise.future()
    }

    override fun getArtifactInsights(
        workspaceId: String,
        artifact: ArtifactQualifiedName,
        types: JsonArray
    ): Future<JsonObject> {
        log.info("Getting artifact insights. Artifact: {} - Workspace: {} - Insights: {}", artifact, workspaceId, types)
        val workspace = InsightWorkspaceProvider.getWorkspace(workspaceId)
        if (workspace == null) {
            log.error("Workspace {} not found", workspaceId)
            return Future.failedFuture("Workspace $workspaceId not found")
        }

        val psiFile = workspace.getPsiFile(
            File("/tmp/$workspaceId/" + artifact.toClass()!!.identifier.substringAfterLast(".") + ".kt")
        ) ?: workspace.getPsiFile(
            File("/tmp/$workspaceId/" + artifact.toClass()!!.identifier.substringAfterLast(".") + ".java")
        )!!
        println(psiFile)

        val promise = Promise.promise<JsonObject>()
        launch(vertx.dispatcher()) {
            val insights = JsonObject()
            types.list.forEach { insightType ->
                InsightProcessor.moderators.mapNotNull {
                    if (it.type == InsightType.valueOf(insightType.toString())) {
                        async {
                            it.addAvailableInsights(psiFile, artifact, insights)
                        }
                    } else null
                }.awaitAll()
            }
            promise.complete(insights)
        }
        return promise.future()
    }

    override fun getProjectClasses(workspaceId: String, offset: Int, limit: Int): Future<JsonArray> {
        log.info("Getting project classes. Workspace: {}", workspaceId)
        val workspace = InsightWorkspaceProvider.getWorkspace(workspaceId)
        if (workspace == null) {
            log.error("Workspace {} not found", workspaceId)
            return Future.failedFuture("Workspace $workspaceId not found")
        }

        val testClasses = workspace.getAllClasses().toSet()
        return Future.succeededFuture(JsonArray(testClasses.map {
            it.getFullyQualifiedName().identifier
        }))
    }

    override fun getProjectFunctions(workspaceId: String, offset: Int, limit: Int): Future<JsonArray> {
        log.info("Getting project functions. Workspace: {}", workspaceId)
        val workspace = InsightWorkspaceProvider.getWorkspace(workspaceId)
        if (workspace == null) {
            log.error("Workspace {} not found", workspaceId)
            return Future.failedFuture("Workspace $workspaceId not found")
        }

        val testFunctions = workspace.getAllFunctions().toSet()
        return Future.succeededFuture(JsonArray(testFunctions.map {
            it.toArtifact()?.getFullyQualifiedName()?.identifier
        }))
    }

    override fun getFunctionCode(workspaceId: String, function: ArtifactQualifiedName): Future<JsonObject> {
        log.info("Getting function code. Workspace: {} - Function: {}", workspaceId, function)
        val workspace = InsightWorkspaceProvider.getWorkspace(workspaceId)
        if (workspace == null) {
            log.error("Workspace {} not found", workspaceId)
            return Future.failedFuture("Workspace $workspaceId not found")
        }

        val workspaceFunctions = workspace.getAllFunctions()
        log.info("Workspace functions: {}", workspaceFunctions)
        val functionArtifacts = workspace.getAllFunctions().mapNotNull { it.toArtifact() }
        val functionNames = functionArtifacts.map { it.getFullyQualifiedName() }
        log.info("Workspace function names: {}", functionNames)

        val projectFunction = functionArtifacts.find { it.getFullyQualifiedName() == function }
        if (projectFunction == null) {
            log.error("Function not found: {}", function)
            return Future.failedFuture("Function not found: $function")
        }

        val code = JsonObject()
        code.put("code", "    " + projectFunction.text) //todo: spacing
        return Future.succeededFuture(code)
    }
}
