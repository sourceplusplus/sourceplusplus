/*
 * Source++, the continuous feedback platform for developers.
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
package spp.platform.core.interceptors

import com.google.common.net.HttpHeaders
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import io.vertx.core.Vertx
import io.vertx.core.http.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.DataRedaction
import spp.protocol.platform.auth.RedactionType
import java.util.regex.Pattern

class SkyWalkingGraphqlInterceptor(private val router: Router) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override suspend fun start() {
        val swHost = config.getJsonObject("skywalking-core").getString("host")
        val swRestPort = config.getJsonObject("skywalking-core").getString("rest_port").toInt()
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
            log.trace { "Forwarding SkyWalking request: {}".args(body) }

            launch {
                val forward = try {
                    httpClient.request(
                        RequestOptions().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                            .setMethod(method).setPort(swRestPort).setHost(swHost).setURI("/graphql")
                    ).await()
                } catch (e: Exception) {
                    log.error(e) { "Failed to forward SkyWalking request: {}".args(body) }
                    req.fail(500, e.message)
                    return@launch
                }

                val selfId = request.getString("developer_id")
                val tenantId = request.getString("tenant_id")
                if (tenantId != null) {
                    Vertx.currentContext().putLocal("tenant_id", tenantId)
                } else {
                    Vertx.currentContext().removeLocal("tenant_id")
                }

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

                        log.trace { "Forwarding SkyWalking response: {}".args(respBody.toString()) }
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

            val tenantId = req.user()?.principal()?.getString("tenant_id")
            forwardSkyWalkingRequest(req.bodyAsString, req.request(), selfId, tenantId)
        }
    }

    private fun forwardSkyWalkingRequest(body: String, req: HttpServerRequest, developerId: String, tenantId: String?) {
        val forward = JsonObject()
        forward.put("developer_id", developerId)
        forward.put("tenant_id", tenantId)
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
