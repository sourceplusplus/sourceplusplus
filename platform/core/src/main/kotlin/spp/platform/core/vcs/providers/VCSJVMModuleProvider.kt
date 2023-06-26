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

import io.grpc.stub.StreamObserver
import org.apache.skywalking.apm.network.common.v3.Commands
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.oal.rt.OALEngineLoaderService
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.receiver.jvm.provider.JVMModuleProvider
import org.apache.skywalking.oap.server.receiver.jvm.provider.JVMOALDefine
import org.apache.skywalking.oap.server.receiver.jvm.provider.handler.JVMMetricReportServiceHandler
import org.apache.skywalking.oap.server.receiver.jvm.provider.handler.JVMMetricReportServiceHandlerCompat
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import spp.platform.core.vcs.ServiceVCS

/**
 * Overrides the default [JVMMetricReportServiceHandler] to add the VCS service name to the request.
 */
class VCSJVMModuleProvider : JVMModuleProvider() {

    override fun name(): String = "spp-receiver-jvm"

    override fun start() {
        // load official analysis
        manager.find(CoreModule.NAME)
            .provider()
            .getService(OALEngineLoaderService::class.java)
            .load(JVMOALDefine.INSTANCE)

        val grpcHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(GRPCHandlerRegister::class.java)
        val jvmMetricReportServiceHandler =
            VCSJVMMetricReportServiceHandler(manager)
        grpcHandlerRegister.addHandler(jvmMetricReportServiceHandler)
        grpcHandlerRegister.addHandler(JVMMetricReportServiceHandlerCompat(jvmMetricReportServiceHandler))
    }

    private class VCSJVMMetricReportServiceHandler(
        manager: ModuleManager,
        private val delegate: JVMMetricReportServiceHandler = JVMMetricReportServiceHandler(manager)
    ) : JVMMetricReportServiceHandler(manager) {
        override fun collect(request: JVMMetricCollection, responseObserver: StreamObserver<Commands>) =
            delegate.collect(
                request.toBuilder().setService(
                    ServiceVCS.getServiceName(request)
                ).build(), responseObserver
            )
    }
}
