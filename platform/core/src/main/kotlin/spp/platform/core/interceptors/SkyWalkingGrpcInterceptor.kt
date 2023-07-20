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
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import spp.platform.common.util.ContextUtil
import spp.platform.storage.SourceStorage
import java.util.concurrent.TimeUnit

class SkyWalkingGrpcInterceptor(
    private val vertx: Vertx,
    private val config: JsonObject
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
        val authHeader = headers?.get(AUTH_HEAD_HEADER_NAME)
        if (authHeader != null && probeAuthCache.getIfPresent(authHeader) != null) {
            val authParts = authHeader.split(":")
            val clientId = authParts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "null" }
            val clientSecret = authParts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
            val tenantId = authParts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" }
            val environment = authParts.getOrNull(3)?.takeIf { it.isNotBlank() && it != "null" }
            val commitId = authParts.getOrNull(4)?.takeIf { it.isNotBlank() && it != "null" }

            val context = Context.current()
                .withValue(ContextUtil.CLIENT_ID, clientId)
                .withValue(ContextUtil.CLIENT_ACCESS, clientSecret)
                .withValue(ContextUtil.TENANT_ID, tenantId)
                .withValue(ContextUtil.ENVIRONMENT, environment)
                .withValue(ContextUtil.VERSION, commitId)
            return Contexts.interceptCall(context, call, headers, next)
        } else {
            val authEnabled = config.getJsonObject("client-access")?.getString("enabled")?.toBooleanStrictOrNull()
            if (authEnabled == true) {
                val authParts = authHeader?.split(":") ?: emptyList()
                val clientId = authParts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "null" }
                val clientSecret = authParts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" }
                val tenantId = authParts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" }
                val environment = authParts.getOrNull(3)?.takeIf { it.isNotBlank() && it != "null" }
                val version = authParts.getOrNull(4)?.takeIf { it.isNotBlank() && it != "null" }
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
                        log.debug {
                            buildString {
                                append("Validated auth header: ")
                                append(authHeader)
                                append(". Client ID: ").append(clientId)
                                append(". Tenant ID: ").append(tenantId)
                                append(". Environment: ").append(environment)
                                append(". Version: ").append(version)
                            }
                        }
                        probeAuthCache.put(authHeader, true)

                        val context = Context.current()
                            .withValue(ContextUtil.CLIENT_ID, clientId)
                            .withValue(ContextUtil.CLIENT_ACCESS, clientSecret)
                            .withValue(ContextUtil.TENANT_ID, tenantId)
                            .withValue(ContextUtil.ENVIRONMENT, environment)
                            .withValue(ContextUtil.VERSION, version)
                        Contexts.interceptCall(context, call, headers, next)
                    }
                }
            } else {
                return next.startCall(call, headers)
            }
        }
    }
}
