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
import org.apache.skywalking.apm.network.language.agent.v3.SegmentCollection
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister
import org.apache.skywalking.oap.server.core.server.HTTPHandlerRegister
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import org.apache.skywalking.oap.server.receiver.trace.provider.TraceModuleProvider
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v8.grpc.SpanAttachedEventReportServiceHandler
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v8.grpc.TraceSegmentReportServiceHandler
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v8.grpc.TraceSegmentReportServiceHandlerCompat
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v8.rest.TraceSegmentReportHandler
import spp.platform.core.vcs.ServiceVCS

/**
 * Overrides the default [TraceSegmentReportServiceHandler] to add the VCS service name to the request.
 */
class VCSTraceModuleProvider : TraceModuleProvider() {

    override fun name(): String = "spp-receiver-trace"

    override fun start() {
        val grpcHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(GRPCHandlerRegister::class.java)
        val httpHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(HTTPHandlerRegister::class.java)

        val traceSegmentReportServiceHandler = VCSTraceSegmentReportServiceHandler(manager)
        grpcHandlerRegister.addHandler(traceSegmentReportServiceHandler)
        grpcHandlerRegister.addHandler(TraceSegmentReportServiceHandlerCompat(traceSegmentReportServiceHandler))
        grpcHandlerRegister.addHandler(SpanAttachedEventReportServiceHandler(manager))

        httpHandlerRegister.addHandler(
            TraceSegmentReportHandler(manager),
            listOf(HttpMethod.POST)
        )
    }

    private class VCSTraceSegmentReportServiceHandler(
        moduleManager: ModuleManager,
        private val delegate: TraceSegmentReportServiceHandler = TraceSegmentReportServiceHandler(moduleManager)
    ) : TraceSegmentReportServiceHandler(moduleManager) {

        override fun collect(responseObserver: StreamObserver<Commands>): StreamObserver<SegmentObject> {
            val streamObserver = delegate.collect(responseObserver)
            return object : StreamObserver<SegmentObject> {
                override fun onNext(value: SegmentObject) = streamObserver.onNext(
                    value.toBuilder().setService(
                        ServiceVCS.getServiceName(value)
                    ).build()
                )

                override fun onError(t: Throwable) = streamObserver.onError(t)
                override fun onCompleted() = streamObserver.onCompleted()
            }
        }

        override fun collectInSync(request: SegmentCollection, responseObserver: StreamObserver<Commands>) {
            delegate.collectInSync(request, object : StreamObserver<Commands> {
                override fun onNext(value: Commands) {
                    //todo: ContinuousProfilingPolicyQuery.ServiceName
                    responseObserver.onNext(value)
                }

                override fun onError(t: Throwable) = responseObserver.onError(t)
                override fun onCompleted() = responseObserver.onCompleted()
            })
        }
    }
}
