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

import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import spp.processor.insight.InsightProcessor.instrumentService
import spp.processor.insight.impl.moderate.model.LiveInsightRequest

/**
 * Holds the queue of insights to be collected and prioritized by moderators for a given workspace.
 */
class WorkspaceInsightQueue : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}
    private val pendingQueue = MergingUpdateQueue(
        "workspace-insight-queue", 1000,
        true, null, null, null, false
    )
    private val activeInstruments = HashSet<LiveInsightRequest>() //todo: get active instruments from service

    fun offer(request: LiveInsightRequest) {
        pendingQueue.queue(Update.create(request) {
            synchronized(pendingQueue) {
                performRequest(request)
            }
        })
    }

    fun get(id: String): LiveInsightRequest? {
        return activeInstruments.find { it.liveInstrument.id == id }
    }

    private fun performRequest(request: LiveInsightRequest): Boolean {
        if (activeInstruments.contains(request)) {
            log.trace("Insight request already active: {}", request)
            return true
        }

        //new request, see if priority is high enough to be active
        val lowestActivePriority = activeInstruments.minOfOrNull { it.priority } ?: 0
        if (request.priority >= lowestActivePriority) {
            //priority is equal to/higher than the lowest active instrument, so activate
            //todo: cap limits
            processRequest(request)
        } else {
            //priority too low, ignore
            log.trace(
                "Ignoring insight request with priority {}. Lowest active priority: {}",
                request.priority, lowestActivePriority
            )
        }
        return false
    }

    private fun processRequest(request: LiveInsightRequest) {
        log.info("Processing insight request: {}", request)
        request.moderator.preSetupInsight(request)
        val addedInstrument = runBlocking(vertx.dispatcher()) {
            instrumentService.addLiveInstrument(request.liveInstrument).await()
        }
        val fulfilledRequest = request.copy(liveInstrument = addedInstrument)
        activeInstruments.add(fulfilledRequest)
        request.moderator.postSetupInsight(fulfilledRequest)
        log.info("Applied insight instrument: {}", addedInstrument)
        log.info("Active insight instruments: {}", activeInstruments.size)
    }
}
