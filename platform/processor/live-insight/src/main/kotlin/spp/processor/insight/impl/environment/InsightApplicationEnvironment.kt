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

import com.intellij.DynamicBundle
import com.intellij.codeInsight.ContainerProvider
import com.intellij.codeInsight.runner.JavaMainMethodProvider
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.lang.MetaLanguage
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.fileTypes.FileTypeRegistry.FileTypeDetector
import com.intellij.psi.FileContextProvider
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.impl.JavaClassSupersImpl
import com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider
import com.intellij.psi.meta.MetaDataContributor
import com.intellij.psi.util.JavaClassSupers
import spp.jetbrains.artifact.service.ArtifactModelService
import spp.jetbrains.artifact.service.ArtifactScopeService
import spp.jetbrains.artifact.service.ArtifactTypeService
import spp.jetbrains.marker.SourceMarkerUtils
import spp.jetbrains.marker.jvm.JVMGuideProvider
import spp.jetbrains.marker.jvm.service.*
import spp.jetbrains.marker.service.*

class InsightApplicationEnvironment(parentDisposable: Disposable) : JavaCoreApplicationEnvironment(parentDisposable) {
    init {
        myApplication.registerService(JavaClassSupers::class.java, JavaClassSupersImpl())

        val rootArea = Extensions.getRootArea()
        registerExtensionPoint(rootArea, FileContextProvider.EP_NAME, FileContextProvider::class.java)
        registerExtensionPoint(rootArea, MetaDataContributor.EP_NAME, MetaDataContributor::class.java)
        registerExtensionPoint(rootArea, PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class.java)
        registerExtensionPoint(rootArea, JavaMainMethodProvider.EP_NAME, JavaMainMethodProvider::class.java)
        registerExtensionPoint(rootArea, ContainerProvider.EP_NAME, ContainerProvider::class.java)
        registerExtensionPoint(rootArea, FileTypeDetector.EP_NAME, FileTypeDetector::class.java)
        registerExtensionPoint(rootArea, MetaLanguage.EP_NAME, MetaLanguage::class.java)

        registerApplicationExtensionPoint(
            DynamicBundle.LanguageBundleEP.EP_NAME,
            DynamicBundle.LanguageBundleEP::class.java
        )
        registerApplicationExtensionPoint(SmartPointerAnchorProvider.EP_NAME, SmartPointerAnchorProvider::class.java)

        SourceMarkerUtils.getJvmLanguages().let {
            ArtifactMarkService.addService(JVMArtifactMarkService(), it)
            ArtifactCreationService.addService(JVMArtifactCreationService(), it)
            ArtifactModelService.addService(JVMArtifactModelService(), it)
            ArtifactNamingService.addService(JVMArtifactNamingService(), it)
            ArtifactScopeService.addService(JVMArtifactScopeService(), it)
            ArtifactConditionService.addService(JVMArtifactConditionService(), it)
            ArtifactTypeService.addService(JVMArtifactTypeService(), it)
            SourceGuideProvider.addProvider(JVMGuideProvider(), it)
        }
    }
}
