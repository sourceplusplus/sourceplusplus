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
package spp.processor.insight.impl.environment

import com.intellij.core.CoreJavaFileManager
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.ProjectRootManagerImpl
import com.intellij.psi.*
import com.intellij.psi.impl.PsiNameHelperImpl
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.impl.search.PsiSearchHelperImpl
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import mu.KotlinLogging
import spp.jetbrains.UserData
import spp.jetbrains.artifact.service.ArtifactScopeService
import spp.jetbrains.artifact.service.toArtifact
import spp.jetbrains.marker.jvm.detect.JVMEndpointDetector
import spp.jetbrains.marker.jvm.detect.endpoint.VertxEndpoint
import spp.jetbrains.marker.service.getFullyQualifiedName
import spp.jetbrains.marker.source.SourceFileMarker
import spp.jetbrains.marker.source.mark.guide.MethodGuideMark
import java.io.File
import java.util.*

class InsightEnvironment(
    val workspaceId: String = UUID.randomUUID().toString()
) {

    private val log = KotlinLogging.logger {}
    private val disposable = Disposable {}
    private val applicationEnvironment = InsightApplicationEnvironment(disposable)
    private val projectEnvironment = JavaCoreProjectEnvironment(disposable, applicationEnvironment)
    val project = projectEnvironment.project
    private val projectFiles = mutableListOf<PsiFile>()

    init {
        //System.setProperty("index_root_path", "/tmp/idea")
        //System.setProperty("caches_dir", "/tmp/idea")
        val project = projectEnvironment.project
        val javaFileManager = project.getComponent(JavaFileManager::class.java)!!
        val coreJavaFileManager = javaFileManager as CoreJavaFileManager
        project.registerService(CoreJavaFileManager::class.java, coreJavaFileManager)
        project.registerService(PsiNameHelper::class.java, PsiNameHelperImpl.getInstance())

        project.extensionArea.registerExtensionPoint(
            PsiElementFinder.EP.name,
            PsiElementFinder::class.java.name,
            ExtensionPoint.Kind.INTERFACE
        )

        projectEnvironment.registerProjectComponent(
            ProjectRootManager::class.java,
            ProjectRootManagerImpl(projectEnvironment.project),
        )

        project.registerService(
            com.intellij.psi.search.PsiSearchHelper::class.java,
            PsiSearchHelperImpl(project)
        )
    }

    fun addSourceDirectory(sourceDirectory: File) {
        log.info { "Adding source directory: $sourceDirectory. Workspace id: $workspaceId" }
        val root = applicationEnvironment.localFileSystem.findFileByIoFile(sourceDirectory)!!
        projectEnvironment.addSourcesToClasspath(root)

        sourceDirectory.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach {
            log.info { "Adding source file: $it. Workspace id: $workspaceId" }
            projectFiles.add(getPsiFile(it)!!)
        }
    }

    fun addJarToClasspath(jar: File) {
        log.info { "Adding jar to classpath: $jar. Workspace id: $workspaceId" }
        projectEnvironment.addJarToClassPath(jar)
    }

    fun getPsiFile(file: File): PsiFile? {
        return applicationEnvironment.localFileSystem.findFileByIoFile(file)?.let {
            PsiManager.getInstance(projectEnvironment.project).findFile(it)
        }
    }

    fun getProjectFiles(): List<PsiFile> {
        return projectFiles
//        val query = AllClassesSearch.search(ProjectScope.getProjectScope(env.project), env.project)
//        val classNames: MutableSet<String?> = HashSet()
//        query.forEach(Consumer { aClass: PsiClass ->
//            classNames.add(aClass.qualifiedName)
//        })
//        println(classNames)
//
//        println(JavaStubIndexKeys::class)
//        (FileBasedIndexImpl.getInstance() as FileBasedIndexImpl).loadIndexes()
//        Reflect.on(StubIndex.getInstance()).call("initializeStubIndexes")
//        val allMethods = PsiShortNamesCache.getInstance(env.project).allMethodNames.toList()
    }

    fun getAllFunctions(): List<PsiNamedElement> {
        log.info { "Getting all functions. Workspace id: $workspaceId. Project files: ${projectFiles.size}" }
        return projectFiles.flatMap { ArtifactScopeService.getFunctions(it) }
    }

    fun getAllClasses(): List<PsiNamedElement> {
        log.info { "Getting all classes. Workspace id: $workspaceId. Project files: ${projectFiles.size}" }
        return projectFiles.flatMap { ArtifactScopeService.getClasses(it) }
    }

    suspend fun getAllEndpoints(vertx: Vertx): JsonArray {
        log.info { "Getting all endpoints. Workspace id: $workspaceId. Project files: ${projectFiles.size}" }
        val result = JsonArray()
        val project = projectEnvironment.project
        UserData.vertx(project, vertx)
        val endpointDetector = JVMEndpointDetector(project)
        endpointDetector.detectorSet.removeIf { it is VertxEndpoint } //todo: not this
        getAllFunctions().forEach {
            val guideMark = MethodGuideMark(
                SourceFileMarker(it.containingFile),
                it as PsiNameIdentifierOwner
            )

            val fullyQualifiedName = it.toArtifact()?.getFullyQualifiedName()?.identifier
            endpointDetector.determineEndpointName(guideMark).await().forEach {
                result.add(JsonObject().put("uri", it.name).put("qualifiedName", fullyQualifiedName))
            }
        }
        return result
    }
}
