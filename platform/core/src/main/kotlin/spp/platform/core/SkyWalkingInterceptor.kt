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
package spp.platform.core

import com.google.common.cache.CacheBuilder
import com.google.common.net.HttpHeaders
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.core.json.JsonObject
import io.vertx.core.net.SocketAddress
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.grpc.client.GrpcClient
import io.vertx.grpc.common.GrpcStatus
import io.vertx.grpc.server.GrpcServer
import io.vertx.grpc.server.GrpcServerRequest
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.common.util.CertsToJksOptionsConverter
import spp.platform.common.util.Msg
import spp.platform.storage.SourceStorage
import spp.protocol.platform.PlatformAddress.PROCESSOR_CONNECTED
import spp.protocol.platform.auth.DataRedaction
import spp.protocol.platform.auth.RedactionType
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SkyWalkingInterceptor(private val router: Router) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    //using memory cache to avoid hitting storage for every request
    private val probeAuthCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build<String, Boolean>()

    override suspend fun start() {
        //start grpc proxy once SkyWalking is available
        val processorConnectedConsumer = vertx.eventBus().consumer<JsonObject>(PROCESSOR_CONNECTED)
        processorConnectedConsumer.handler {
            processorConnectedConsumer.unregister()
            startGrpcProxy()
        }

        val swHost = config.getJsonObject("skywalking-oap").getString("host")
        val swRestPort = config.getJsonObject("skywalking-oap").getString("rest_port").toInt()
        val httpClient = vertx.createHttpClient()
        vertx.eventBus().consumer<JsonObject>("skywalking-forwarder") { req ->
            val request = req.body()
            val body = request.getString("body")!!
            val graphqlQuery = JsonObject(body).getString("query")
            val queryDocument = Parser.parse(graphqlQuery)
            val operationAliases = mutableMapOf<String, String>()
            val fieldAliases = mutableMapOf<String, Map<String, String>>()
            queryDocument.definitions.forEach { definition ->
                if (definition is OperationDefinition) {
                    definition.selectionSet.selections.forEach { selection ->
                        if (selection is Field) {
                            if (selection.name == "queryTrace") {
                                operationAliases[selection.alias ?: selection.name] = selection.name
                                fieldAliases[selection.alias ?: selection.name] = captureAliases(selection.selectionSet)
                            }
                        }
                    }
                }
            }

            val headers: JsonObject? = request.getJsonObject("headers")
            val method = HttpMethod.valueOf(request.getString("method"))!!
            log.trace { Msg.msg("Forwarding SkyWalking request: {}", body) }

            launch {
                val forward = try {
                    httpClient.request(
                        RequestOptions().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                            .setMethod(method).setPort(swRestPort).setHost(swHost).setURI("/graphql")
                    ).await()
                } catch (e: Exception) {
                    log.error(e) { Msg.msg("Failed to forward SkyWalking request: {}", body) }
                    req.fail(500, e.message)
                    return@launch
                }

                val selfId = request.getString("developer_id")
                val tenantId = request.getString("tenant_id")
                val redactions = SourceStorage.getDeveloperDataRedactions(selfId)
                forward.response().onSuccess { resp ->
                    resp.body().onSuccess {
                        val respBody = it.toJsonObject()
                        respBody.getJsonObject("data")?.fieldNames()?.forEach {
                            val respObject = respBody.getJsonObject("data").getValue(it)
                            if (operationAliases[it] == "queryTrace" && redactions.isNotEmpty()) {
                                doQueryTraceRedaction(respObject as JsonObject, fieldAliases[it]!!, redactions)
                            }
                        }

                        log.trace { Msg.msg("Forwarding SkyWalking response: {}", respBody.toString()) }
                        val respOb = JsonObject()
                        respOb.put("status", resp.statusCode())
                        respOb.put("body", respBody.toString())
                        req.reply(respOb)
                    }.onFailure {
                        log.error("Failed to read SkyWalking response body: {}", it.message)
                        req.fail(500, it.message)
                    }
                }.onFailure {
                    log.error("Failed to forward SkyWalking response: {}", it.message)
                    req.fail(500, it.message)
                }

                headers?.fieldNames()?.forEach {
                    forward.putHeader(it, headers.getValue(it).toString())
                }
                forward.putHeader("tenant_id", tenantId)
                forward.end(body).await()
            }
        }

        router.route("/graphql/skywalking").handler(BodyHandler.create()).handler { req ->
            var selfId = req.user()?.principal()?.getString("developer_id")
            if (selfId == null) {
                val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
                val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()
                if (jwtEnabled) {
                    req.response().setStatusCode(500).end("Missing self id")
                    return@handler
                } else {
                    selfId = "system"
                }
            }
            forwardSkyWalkingRequest(req.bodyAsString, req.request(), selfId)
        }
    }

    private fun startGrpcProxy() {
        val grpcConfig = config.getJsonObject("spp-platform").getJsonObject("grpc")
        val sslEnabled = grpcConfig.getString("ssl_enabled").toBooleanStrict()
        val options = HttpServerOptions()
            .setSsl(sslEnabled)
            .setUseAlpn(true)
            .apply {
                if (sslEnabled) {
                    val keyFile = File("config/spp-platform.key")
                    val certFile = File("config/spp-platform.crt")
                    val jksOptions = CertsToJksOptionsConverter(
                        certFile.absolutePath, keyFile.absolutePath
                    ).createJksOptions()
                    setKeyStoreOptions(jksOptions)
                }
            }
        val grpcServer = GrpcServer.server(vertx)
        val server = vertx.createHttpServer(options)
        val sppGrpcPort = grpcConfig.getString("port").toInt()
        server.requestHandler(grpcServer).listen(sppGrpcPort)
        log.info("SkyWalking gRPC proxy started. Listening on port: {}", sppGrpcPort)

        val swHost = config.getJsonObject("skywalking-oap").getString("host")
        val swGrpcPort = config.getJsonObject("skywalking-oap").getString("grpc_port").toInt()
        val http2Client = HttpClientOptions()
            .setUseAlpn(true)
            .setVerifyHost(false)
            .setTrustAll(true)
            .setDefaultHost(swHost)
            .setDefaultPort(swGrpcPort)
            .setSsl(sslEnabled)
            .setHttp2ClearTextUpgrade(false)
        val grpcClient = GrpcClient.client(vertx, http2Client)
        val skywalkingGrpcServer = SocketAddress.inetSocketAddress(swGrpcPort, swHost)

        grpcServer.callHandler { req ->
            req.pause()
            val authHeader = req.headers().get("authentication")
            if (authHeader != null && probeAuthCache.getIfPresent(authHeader) != null) {
                proxyGrpcRequest(req, grpcClient, skywalkingGrpcServer)
            } else {
                val authEnabled = config.getJsonObject("client-access")?.getString("enabled")?.toBooleanStrictOrNull()
                if (authEnabled == true) {
                    val authParts = authHeader?.split(":") ?: emptyList()
                    val clientId = authParts.getOrNull(0)
                    val clientSecret = authParts.getOrNull(1)
                    if (clientId == null || clientSecret == null) {
                        req.response().status(GrpcStatus.PERMISSION_DENIED).end()
                        return@callHandler
                    }

                    val tenantId = authParts.getOrNull(2)
                    if (tenantId != null) {
                        Vertx.currentContext().putLocal("tenant_id", tenantId)
                    } else {
                        Vertx.currentContext().removeLocal("tenant_id")
                    }

                    launch(vertx.dispatcher()) {
                        val clientAccess = SourceStorage.getClientAccess(clientId)
                        if (clientAccess == null || clientAccess.secret != clientSecret) {
                            req.response().status(GrpcStatus.PERMISSION_DENIED).end()
                            return@launch
                        } else {
                            probeAuthCache.put(authHeader, true)
                            proxyGrpcRequest(req, grpcClient, skywalkingGrpcServer)
                        }
                    }
                } else {
                    proxyGrpcRequest(req, grpcClient, skywalkingGrpcServer)
                }
            }
        }
    }

    private fun proxyGrpcRequest(
        req: GrpcServerRequest<Buffer, Buffer>,
        grpcClient: GrpcClient,
        skywalkingGrpcServer: SocketAddress
    ) {
        log.trace { "Proxying gRPC call: ${req.fullMethodName()}" }
        grpcClient.request(skywalkingGrpcServer).onSuccess { proxyRequest ->
            req.headers().get("authentication")?.let { proxyRequest.headers().add("authentication", it) }

            proxyRequest.response().onSuccess { proxyResponse ->
                proxyResponse.messageHandler { msg ->
                    log.trace { "Sending stream message. Bytes: ${msg.payload().bytes.size}" }
                    req.response().writeMessage(msg)
                }
                proxyResponse.errorHandler { error ->
                    req.response().status(error.status)
                }
                proxyResponse.endHandler { req.response().end() }
            }.onFailure {
                log.error(it) { "Failed to get proxy response" }
                req.response().status(GrpcStatus.UNKNOWN).end()
            }

            proxyRequest.fullMethodName(req.fullMethodName())
            req.messageHandler { msg ->
                log.trace { "Received stream message. Bytes: ${msg.payload().bytes.size}" }
                proxyRequest.writeMessage(msg)
            }
            req.endHandler { proxyRequest.end() }
            req.resume()
        }.onFailure {
            log.error(it) { "Failed to send proxy request" }
            req.response().status(GrpcStatus.UNKNOWN).end()
            req.resume()
        }
    }

    private fun forwardSkyWalkingRequest(body: String, req: HttpServerRequest, developerId: String) {
        val forward = JsonObject()
        forward.put("developer_id", developerId)
        forward.put("body", body)
        val headers = JsonObject()
        req.headers().names().forEach {
            headers.put(it, req.headers().get(it))
        }
        forward.put("headers", headers)
        forward.put("method", req.method().name())
        vertx.eventBus().request<JsonObject>("skywalking-forwarder", forward) {
            if (it.succeeded()) {
                val resp = it.result().body()
                req.response().setStatusCode(resp.getInteger("status")).end(resp.getString("body"))
            } else {
                log.error("Failed to forward SkyWalking request", it.cause())
                req.response().setStatusCode(500).end(it.cause().message)
            }
        }
    }

    private fun doQueryTraceRedaction(
        data: JsonObject,
        fieldAliases: Map<String, String>,
        redactions: List<DataRedaction>
    ) {
        data.getJsonArray(fieldAliases.getOrDefault("spans", "spans")).forEach {
            val span = it as JsonObject
            val tags = span.getJsonArray(fieldAliases.getOrDefault("tags", "tags"))
            for (i in 0 until tags.size()) {
                val tag = tags.getJsonObject(i)
                doDataRedaction(redactions, tag)
            }

            val logs = span.getJsonArray(fieldAliases.getOrDefault("logs", "logs"))
            for (i in 0 until logs.size()) {
                val logData = logs.getJsonObject(i).getJsonArray("data")
                for (z in 0 until logData.size()) {
                    val log = logData.getJsonObject(z)
                    doDataRedaction(redactions, log)
                }
            }
        }
    }

    private fun doDataRedaction(redactions: List<DataRedaction>, data: JsonObject) {
        redactions.forEach {
            when (it.type) {
                RedactionType.VALUE_REGEX -> {
                    data.put(
                        "value",
                        Pattern.compile(it.lookup).matcher(data.getString("value")).replaceAll(it.replacement)
                    )
                }

                RedactionType.IDENTIFIER_MATCH -> {
                    if (it.lookup == data.getString("key")) {
                        data.put("value", it.replacement)
                    } else if (data.getString("key").startsWith("spp.") &&
                        it.lookup == data.getString("key").substringAfterLast(":")
                    ) {
                        data.put("value", it.replacement)
                    }
                }
            }
        }
    }

    private fun captureAliases(set: SelectionSet): Map<String, String> {
        val aliases = mutableMapOf<String, String>()
        set.selections.forEach {
            if (it is Field) {
                if (it.alias != null) aliases[it.name] = it.alias
                if (it.selectionSet != null) aliases.putAll(captureAliases(it.selectionSet))
            }
        }
        return aliases
    }
}
