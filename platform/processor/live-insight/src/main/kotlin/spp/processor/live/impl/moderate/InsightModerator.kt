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
package spp.processor.live.impl.moderate

import com.intellij.psi.PsiFile
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import spp.processor.live.impl.moderate.model.LiveInsightRequest
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.insight.InsightType

/**
 * Used to moderate the impact of insight collection on an application. Each moderator
 * is responsible for collecting live insight data, storing insight data to the CPG, and
 *  maintaining the priority of insight data.
 */
abstract class InsightModerator : CoroutineVerticle() {

    abstract val type: InsightType

    /**
     * Called before the [request] has been sent to the application.
     */
    open fun preSetupInsight(request: LiveInsightRequest) {}

    /**
     * Called after the [request] has been sent to the application.
     */
    open fun postSetupInsight(request: LiveInsightRequest) {}

    abstract suspend fun addAvailableInsights(
        psiFile: PsiFile,
        artifact: ArtifactQualifiedName,
        insights: JsonObject
    )
}
