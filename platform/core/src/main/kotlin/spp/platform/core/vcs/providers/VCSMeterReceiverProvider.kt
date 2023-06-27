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
import org.apache.skywalking.apm.network.language.agent.v3.MeterData
import org.apache.skywalking.apm.network.language.agent.v3.MeterDataCollection
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.meter.process.IMeterProcessService
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.apache.skywalking.oap.server.receiver.meter.provider.MeterReceiverProvider
import org.apache.skywalking.oap.server.receiver.meter.provider.handler.MeterServiceHandler
import org.apache.skywalking.oap.server.receiver.meter.provider.handler.MeterServiceHandlerCompat
import org.apache.skywalking.oap.server.receiver.sharing.server.SharingServerModule
import spp.platform.core.vcs.ServiceVCS

/**
 * Overrides the default [MeterServiceHandler] to add the VCS service name to the request.
 */
class VCSMeterReceiverProvider : MeterReceiverProvider() {

    override fun name(): String = "spp-receiver-meter"

    override fun start() {
        val processService = manager.find(AnalyzerModule.NAME)
            .provider()
            .getService(IMeterProcessService::class.java)
        val grpcHandlerRegister = manager.find(SharingServerModule.NAME)
            .provider()
            .getService(GRPCHandlerRegister::class.java)
        val meterServiceHandlerCompat = VCSMeterServiceHandler(manager, processService!!)
        grpcHandlerRegister.addHandler(meterServiceHandlerCompat)
        grpcHandlerRegister.addHandler(MeterServiceHandlerCompat(meterServiceHandlerCompat))
    }

    private class VCSMeterServiceHandler(
        manager: ModuleManager,
        processService: IMeterProcessService,
        private val delegate: MeterServiceHandler = MeterServiceHandler(manager, processService)
    ) : MeterServiceHandler(manager, processService) {
        override fun collect(responseObserver: StreamObserver<Commands>): StreamObserver<MeterData> {
            val streamObserver = delegate.collect(responseObserver)
            return object : StreamObserver<MeterData> {
                override fun onNext(value: MeterData) = streamObserver.onNext(
                    value.toBuilder().setService(
                        ServiceVCS.getServiceName(value)
                    ).build()
                )

                override fun onError(t: Throwable) = streamObserver.onError(t)
                override fun onCompleted() = streamObserver.onCompleted()
            }
        }

        override fun collectBatch(responseObserver: StreamObserver<Commands>): StreamObserver<MeterDataCollection> {
            val streamObserver = delegate.collectBatch(responseObserver)
            return object : StreamObserver<MeterDataCollection> {
                override fun onNext(value: MeterDataCollection) = streamObserver.onNext(value)
                override fun onError(t: Throwable) = streamObserver.onError(t)
                override fun onCompleted() = streamObserver.onCompleted()
            }
        }
    }
}
