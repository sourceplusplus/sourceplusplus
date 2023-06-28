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
package spp.processor.insight.impl.moderate

import com.intellij.psi.PsiFile
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.processor.insight.InsightProcessor
import spp.processor.insight.impl.environment.InsightEnvironment
import spp.processor.insight.impl.moderate.model.LiveInsightRequest
import spp.processor.insight.impl.util.BoundedTreeSet
import spp.processor.insight.provider.InsightWorkspaceProvider
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.insight.InsightType

/**
 * Used to moderate the impact of insight collection on an application. Each moderator
 * is responsible for collecting live insight data, storing insight data to the CPG, and
 *  maintaining the priority of insight data.
 */
abstract class InsightModerator : CoroutineVerticle() { //todo: InsightSensor?

    private val log = KotlinLogging.logger {}
    protected val offerQueue = BoundedTreeSet<LiveInsightRequest>(100)

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

    abstract suspend fun searchProject(environment: InsightEnvironment)

    override suspend fun start() {
        startSearchLoop()
        startOfferLoop()
    }

    private fun startSearchLoop() {
        vertx.setPeriodic(1000) {
            log.trace("Checking for insights to gather")
            launch(vertx.dispatcher()) {
                searchProject(InsightWorkspaceProvider.insightEnvironment)
            }
        }
    }

    private fun startOfferLoop() {
        vertx.setPeriodic(1000) {
            log.trace("Checking for insights to offer")
            val l = offerQueue.toList()
            l.forEach {
                InsightProcessor.workspaceQueue.offer(it)
            }
        }
    }
}
