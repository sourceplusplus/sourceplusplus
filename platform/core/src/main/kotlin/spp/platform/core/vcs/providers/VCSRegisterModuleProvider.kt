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

import com.linecorp.armeria.common.HttpMethod
import io.grpc.stub.StreamObserver
import org.apache.skywalking.apm.network.common.v3.Commands
import org.apache.skywalking.apm.network.management.v3.InstancePingPkg
import org.apache.skywalking.apm.network.management.v3.InstanceProperties
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister
import org.apache.skywalking.oap.server.core.server.HTTPHandlerRegister
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.receiver.register.provider.RegisterModuleProvider
import org.apache.skywalking.oap.server.receiver.register.provider.handler.v8.grpc.ManagementServiceGRPCHandler
import org.apache.skywalking.oap.server.receiver.register.provider.handler.v8.grpc.ManagementServiceGrpcHandlerCompat
import org.apache.skywalking.oap.server.receiver.register.provider.handler.v8.rest.ManagementServiceHTTPHandler
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import spp.platform.core.vcs.ServiceVCS

/**
 * Overrides the default [ManagementServiceGRPCHandler] to add the VCS service name to the request.
 */
class VCSRegisterModuleProvider : RegisterModuleProvider() {

    override fun name(): String = "spp-receiver-register"

    override fun start() {
        val grpcHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(GRPCHandlerRegister::class.java)
        val managementServiceHTTPHandler =
            VCSManagementServiceGRPCHandler(manager)
        grpcHandlerRegister.addHandler(managementServiceHTTPHandler)
        grpcHandlerRegister.addHandler(ManagementServiceGrpcHandlerCompat(managementServiceHTTPHandler))

        val httpHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(HTTPHandlerRegister::class.java)
        httpHandlerRegister.addHandler(ManagementServiceHTTPHandler(manager), listOf(HttpMethod.POST))
    }

    private class VCSManagementServiceGRPCHandler(
        manager: ModuleManager,
        private val delegate: ManagementServiceGRPCHandler = ManagementServiceGRPCHandler(manager)
    ) : ManagementServiceGRPCHandler(manager) {

        override fun reportInstanceProperties(request: InstanceProperties, responseObserver: StreamObserver<Commands>) =
            delegate.reportInstanceProperties(
                request.toBuilder().setService(
                    ServiceVCS.getServiceName(request)
                ).build(), responseObserver
            )

        override fun keepAlive(request: InstancePingPkg, responseObserver: StreamObserver<Commands>) =
            delegate.keepAlive(
                request.toBuilder().setService(
                    ServiceVCS.getServiceName(request)
                ).build(), responseObserver
            )
    }
}
