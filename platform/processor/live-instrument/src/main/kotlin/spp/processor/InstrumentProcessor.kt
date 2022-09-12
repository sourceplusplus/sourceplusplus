/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
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
package spp.processor

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import io.vertx.core.*
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.types.EventBusService
import io.vertx.serviceproxy.ServiceBinder
import io.vertx.serviceproxy.ServiceInterceptor
import org.apache.skywalking.oap.server.core.analysis.metrics.DataTable
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.FeedbackProcessor
import spp.processor.live.impl.LiveInstrumentProcessorImpl
import spp.protocol.SourceServices
import spp.protocol.platform.auth.AccessChecker
import spp.protocol.platform.auth.RolePermission
import spp.protocol.platform.auth.RolePermission.*
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.error.InstrumentAccessDenied
import spp.protocol.service.error.PermissionAccessDenied
import kotlin.system.exitProcess

object InstrumentProcessor : FeedbackProcessor() {

    private val log = LoggerFactory.getLogger(InstrumentProcessor::class.java)
    private var liveInstrumentRecord: Record? = null
    val liveInstrumentProcessor = LiveInstrumentProcessorImpl()

    override fun bootProcessor(moduleManager: ModuleManager) {
        module = moduleManager

        log.info("InstrumentProcessor initialized")
        connectToPlatform()
    }

    override fun onConnected(vertx: Vertx) {
        log.info("Deploying source processor")
        vertx.deployVerticle(InstrumentProcessor) {
            if (it.succeeded()) {
                processorVerticleId = it.result()
            } else {
                log.error("Failed to deploy source processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    override suspend fun start() {
        log.info("Starting InstrumentProcessorVerticle")
        val module = SimpleModule()
        module.addSerializer(DataTable::class.java, object : JsonSerializer<DataTable>() {
            override fun serialize(value: DataTable, gen: JsonGenerator, provider: SerializerProvider) {
                val data = mutableMapOf<String, Long>()
                value.keys().forEach { data[it] = value.get(it) }
                gen.writeStartObject()
                data.forEach {
                    gen.writeNumberField(it.key, it.value)
                }
                gen.writeEndObject()
            }
        })
        DatabindCodec.mapper().registerModule(module)

        vertx.deployVerticle(liveInstrumentProcessor).await()

        ServiceBinder(vertx).setIncludeDebugInfo(true)
            .addInterceptor(developerAuthInterceptor())
            .addInterceptor(permissionAndAccessCheckInterceptor())
            .setAddress(SourceServices.Utilize.LIVE_INSTRUMENT)
            .register(LiveInstrumentService::class.java, liveInstrumentProcessor)
        liveInstrumentRecord = EventBusService.createRecord(
            SourceServices.Utilize.LIVE_INSTRUMENT,
            SourceServices.Utilize.LIVE_INSTRUMENT,
            LiveInstrumentService::class.java,
            JsonObject().put("INSTANCE_ID", INSTANCE_ID)
        )
        discovery.publish(liveInstrumentRecord) {
            if (it.succeeded()) {
                log.info("Live instrument processor published")
            } else {
                log.error("Failed to publish live instrument processor", it.cause())
                exitProcess(-1)
            }
        }
    }

    private fun permissionAndAccessCheckInterceptor(): ServiceInterceptor {
        return ServiceInterceptor { _, _, msg ->
            val promise = Promise.promise<Message<JsonObject>>()
            val liveManagementService = LiveManagementService.createProxy(vertx, msg.headers().get("auth-token"))
            liveManagementService.getSelf().onSuccess { selfInfo ->
                validateRolePermission(selfInfo, msg) {
                    if (it.succeeded()) {
                        if (msg.headers().get("action").startsWith("addLiveInstrument")) {
                            validateInstrumentAccess(selfInfo, msg, promise)
                        } else {
                            promise.complete(msg)
                        }
                    } else {
                        promise.fail(it.cause())
                    }
                }
            }.onFailure {
                promise.fail(it)
            }
            promise.future()
        }
    }

    private fun validateRolePermission(
        selfInfo: SelfInfo,
        msg: Message<JsonObject>,
        handler: Handler<AsyncResult<Message<JsonObject>>>
    ) {
        if (msg.headers().get("action") == "addLiveInstruments") {
            val batchPromise = Promise.promise<Message<JsonObject>>()
            msg.body().getJsonArray("instruments").list.forEach {
                val instrumentType = JsonObject.mapFrom(it).getString("type")
                val necessaryPermission = RolePermission.valueOf("ADD_LIVE_$instrumentType")
                if (!selfInfo.permissions.contains(necessaryPermission)) {
                    log.warn("User ${selfInfo.developer.id} missing permission: $necessaryPermission")
                    batchPromise.fail(PermissionAccessDenied(necessaryPermission).toEventBusException())
                }
            }
            batchPromise.tryComplete(msg)
            handler.handle(batchPromise.future())
        } else if (msg.headers().get("action") == "addLiveInstrument") {
            val instrumentType = msg.body().getJsonObject("instrument").getString("type")
            val necessaryPermission = RolePermission.valueOf("ADD_LIVE_$instrumentType")
            if (selfInfo.permissions.contains(necessaryPermission)) {
                handler.handle(Future.succeededFuture(msg))
            } else {
                log.warn("User ${selfInfo.developer.id} missing permission: $necessaryPermission")
                handler.handle(Future.failedFuture(PermissionAccessDenied(necessaryPermission).toEventBusException()))
            }
        } else if (msg.headers().get("action").startsWith("removeLiveInstrument")) {
            if (selfInfo.permissions.contains(REMOVE_LIVE_INSTRUMENT)) {
                handler.handle(Future.succeededFuture(msg))
            } else {
                log.warn("User ${selfInfo.developer.id} missing permission: $REMOVE_LIVE_INSTRUMENT")
                handler.handle(Future.failedFuture(PermissionAccessDenied(REMOVE_LIVE_INSTRUMENT).toEventBusException()))
            }
        } else if (msg.headers().get("action").startsWith("getLiveInstrument")) {
            if (selfInfo.permissions.contains(GET_LIVE_INSTRUMENTS)) {
                handler.handle(Future.succeededFuture(msg))
            } else {
                log.warn("User ${selfInfo.developer.id} missing permission: $GET_LIVE_INSTRUMENTS")
                handler.handle(Future.failedFuture(PermissionAccessDenied(GET_LIVE_INSTRUMENTS).toEventBusException()))
            }
        } else if (msg.headers().get("action") == "clearLiveInstruments") {
            if (selfInfo.permissions.contains(CLEAR_ALL_LIVE_INSTRUMENTS)) {
                handler.handle(Future.succeededFuture(msg))
            } else {
                log.warn("User ${selfInfo.developer.id} missing permission: $CLEAR_ALL_LIVE_INSTRUMENTS")
                handler.handle(Future.failedFuture(PermissionAccessDenied(CLEAR_ALL_LIVE_INSTRUMENTS).toEventBusException()))
            }
        } else if (RolePermission.fromString(msg.headers().get("action")) != null) {
            val necessaryPermission = RolePermission.fromString(msg.headers().get("action"))!!
            if (selfInfo.permissions.contains(necessaryPermission)) {
                handler.handle(Future.succeededFuture(msg))
            } else {
                log.warn("User ${selfInfo.developer.id} missing permission: $necessaryPermission")
                handler.handle(Future.failedFuture(PermissionAccessDenied(necessaryPermission).toEventBusException()))
            }
        } else {
            TODO()
        }
    }

    private fun validateInstrumentAccess(
        selfInfo: SelfInfo,
        msg: Message<JsonObject>,
        promise: Promise<Message<JsonObject>>
    ) {
        if (msg.headers().get("action") == "addLiveInstruments") {
            val instruments = msg.body().getJsonArray("instruments")
            for (i in 0 until instruments.size()) {
                val sourceLocation = instruments.getJsonObject(i)
                    .getJsonObject("location").getString("source")
                if (!AccessChecker.hasInstrumentAccess(selfInfo.access, sourceLocation)) {
                    log.warn(
                        "Rejected developer {} unauthorized instrument access to: {}",
                        selfInfo.developer.id, sourceLocation
                    )
                    val replyEx = ReplyException(
                        ReplyFailure.RECIPIENT_FAILURE, 403,
                        Json.encode(InstrumentAccessDenied(sourceLocation).toEventBusException())
                    )
                    promise.fail(replyEx)
                    return
                }
            }
            promise.complete(msg)
        } else {
            val sourceLocation = msg.body().getJsonObject("instrument")
                .getJsonObject("location").getString("source")
            if (!AccessChecker.hasInstrumentAccess(selfInfo.access, sourceLocation)) {
                log.warn(
                    "Rejected developer {} unauthorized instrument access to: {}",
                    selfInfo.developer.id, sourceLocation
                )
                promise.fail(InstrumentAccessDenied(sourceLocation).toEventBusException())
            } else {
                promise.complete(msg)
            }
        }
    }

    override suspend fun stop() {
        log.info("Stopping InstrumentProcessorVerticle")
        discovery.unpublish(liveInstrumentRecord!!.registration).onComplete {
            if (it.succeeded()) {
                log.info("Live instrument processor unpublished")
            } else {
                log.error("Failed to unpublish live instrument processor", it.cause())
            }
        }.await()
    }
}
