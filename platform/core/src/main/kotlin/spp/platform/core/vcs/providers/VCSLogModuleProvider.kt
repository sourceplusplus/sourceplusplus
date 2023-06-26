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
import org.apache.skywalking.apm.network.logging.v3.LogData
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister
import org.apache.skywalking.oap.server.core.server.HTTPHandlerRegister
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import org.apache.skywalking.oap.server.recevier.log.provider.LogModuleProvider
import org.apache.skywalking.oap.server.recevier.log.provider.handler.grpc.LogReportServiceGrpcHandler
import org.apache.skywalking.oap.server.recevier.log.provider.handler.rest.LogReportServiceHTTPHandler
import spp.platform.core.vcs.ServiceVCS

/**
 * Overrides the default [LogReportServiceGrpcHandler] to add the VCS service name to the request.
 */
class VCSLogModuleProvider : LogModuleProvider() {

    override fun name(): String = "spp-receiver-log"

    override fun start() {
        val grpcHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(GRPCHandlerRegister::class.java)

        grpcHandlerRegister.addHandler(VCSLogReportServiceGrpcHandler(manager))

        val httpHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(HTTPHandlerRegister::class.java)
        httpHandlerRegister.addHandler(LogReportServiceHTTPHandler(manager), listOf(HttpMethod.POST))
    }

    private class VCSLogReportServiceGrpcHandler(
        manager: ModuleManager,
        private val delegate: LogReportServiceGrpcHandler = LogReportServiceGrpcHandler(manager)
    ) : LogReportServiceGrpcHandler(manager) {
        override fun collect(responseObserver: StreamObserver<Commands>): StreamObserver<LogData> {
            val streamObserver = delegate.collect(responseObserver)
            return object : StreamObserver<LogData> {
                override fun onNext(value: LogData) = streamObserver.onNext(
                    value.toBuilder().setService(
                        ServiceVCS.getServiceName(value)
                    ).build()
                )

                override fun onError(t: Throwable) = streamObserver.onError(t)
                override fun onCompleted() = streamObserver.onCompleted()
            }
        }
    }
}
