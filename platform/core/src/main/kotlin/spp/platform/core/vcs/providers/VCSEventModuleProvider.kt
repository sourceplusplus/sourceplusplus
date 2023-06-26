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
package spp.platform.core.vcs.providers

import org.apache.skywalking.apm.network.event.v3.Event
import org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerModule
import org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerModuleProvider
import org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerService
import org.apache.skywalking.oap.server.analyzer.event.EventAnalyzerServiceImpl
import org.apache.skywalking.oap.server.analyzer.event.listener.EventAnalyzerListener
import org.apache.skywalking.oap.server.library.module.ModuleManager
import spp.platform.core.vcs.ServiceVCS

/**
 * Overrides the default [EventAnalyzerServiceImpl] to add the VCS service name to the request.
 */
class VCSEventModuleProvider : EventAnalyzerModuleProvider() {

    override fun name(): String = "spp-event-analyzer"

    override fun start() {
        super.start()

        val analyzerService = manager.find(EventAnalyzerModule.NAME).provider()
            .getService(EventAnalyzerService::class.java) as EventAnalyzerServiceImpl
        registerServiceImplementation(
            EventAnalyzerService::class.java,
            VCSEventAnalyzerService(analyzerService, manager)
        )
    }

    private class VCSEventAnalyzerService(
        private val delegate: EventAnalyzerServiceImpl,
        manager: ModuleManager
    ) : EventAnalyzerServiceImpl(manager) {
        override fun analyze(event: Event) = delegate.analyze(
            event.toBuilder().setSource(
                event.source.toBuilder().setService(
                    ServiceVCS.getServiceName(event)
                ).build()
            ).build()
        )

        override fun add(factory: EventAnalyzerListener.Factory) = delegate.add(factory)
        override fun getEventAnalyzerListenerFactories(): MutableList<EventAnalyzerListener.Factory> =
            delegate.eventAnalyzerListenerFactories
    }
}
