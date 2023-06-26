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
package spp.platform.core.interceptors

import com.google.common.cache.CacheBuilder
import io.grpc.*
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import spp.platform.common.util.ContextUtil
import spp.platform.storage.SourceStorage
import spp.protocol.platform.status.InstanceConnection
import spp.protocol.service.LiveManagementService
import java.util.concurrent.TimeUnit

class SkyWalkingGrpcInterceptor(
    private val vertx: Vertx,
    private val config: JsonObject,
    private val managementService: LiveManagementService
) : ServerInterceptor {

    companion object {
        private val log = KotlinLogging.logger {}
        private val AUTH_HEAD_HEADER_NAME = Metadata.Key.of("Authentication", Metadata.ASCII_STRING_MARSHALLER)
    }

    //using memory cache to avoid hitting storage for every request
    private val probeAuthCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build<String, Boolean>()

    /**
     * Intercepts gRPC calls and checks for authentication, adds VCS data to the context, and adds the tenant ID to the
     * context if it is present in the auth header.
     */
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata?,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val clients = runBlocking(vertx.dispatcher()) { managementService.getClients().await() }
        val clientOb = clients.getJsonArray("probes").firstOrNull() as? JsonObject//todo: find real client
        val client = clientOb?.let { InstanceConnection(it) }
        val commitId = (client?.meta?.get("application") as? JsonObject)?.getString("git_commit")

        val authHeader = headers?.get(AUTH_HEAD_HEADER_NAME)
        if (authHeader != null && probeAuthCache.getIfPresent(authHeader) != null) {
            val authParts = authHeader.split(":")
            val clientId = authParts.getOrNull(0)
            val clientSecret = authParts.getOrNull(1)
            val tenantId = authParts.getOrNull(2)

            val context = Context.current()
                .withValue(ContextUtil.CLIENT_ID, clientId)
                .withValue(ContextUtil.CLIENT_ACCESS, clientSecret)
                .withValue(ContextUtil.TENANT_ID, tenantId)
                .withValue(ContextUtil.COMMIT_ID, commitId)
            return Contexts.interceptCall(context, call, headers, next)
        } else {
            val authEnabled = config.getJsonObject("client-access")?.getString("enabled")?.toBooleanStrictOrNull()
            if (authEnabled == true) {
                val authParts = authHeader?.split(":") ?: emptyList()
                val clientId = authParts.getOrNull(0)
                val clientSecret = authParts.getOrNull(1)
                val tenantId = authParts.getOrNull(2)
                if (authHeader == null || clientId == null || clientSecret == null) {
                    log.warn { "Invalid auth header: $authHeader" }
                    call.close(Status.PERMISSION_DENIED, Metadata())
                    return object : ServerCall.Listener<ReqT>() {}
                }

                return runBlocking(vertx.dispatcher()) {
                    if (tenantId != null) {
                        Vertx.currentContext().putLocal("tenant_id", tenantId)
                    } else {
                        Vertx.currentContext().removeLocal("tenant_id")
                    }

                    val clientAccess = SourceStorage.getClientAccess(clientId)
                    return@runBlocking if (clientAccess == null || clientAccess.secret != clientSecret) {
                        log.warn { "Invalid auth header: $authHeader" }
                        call.close(Status.PERMISSION_DENIED, Metadata())
                        object : ServerCall.Listener<ReqT>() {}
                    } else {
                        probeAuthCache.put(authHeader, true)

                        val context = Context.current()
                            .withValue(ContextUtil.CLIENT_ID, clientId)
                            .withValue(ContextUtil.CLIENT_ACCESS, clientSecret)
                            .withValue(ContextUtil.TENANT_ID, tenantId)
                            .withValue(ContextUtil.COMMIT_ID, commitId)
                        Contexts.interceptCall(context, call, headers, next)
                    }
                }
            } else {
                return next.startCall(call, headers)
            }
        }
    }
}
