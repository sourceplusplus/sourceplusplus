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

import com.google.common.net.HttpHeaders
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.RequestOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.core.util.Msg
import spp.protocol.platform.auth.DataRedaction
import spp.protocol.platform.auth.RedactionType
import java.util.regex.Pattern

class SkyWalkingInterceptor(private val router: Router) : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override suspend fun start() {
        val skywalkingHost = config.getJsonObject("skywalking-oap").getString("host")
        val skywalkingPort = config.getJsonObject("skywalking-oap").getString("port").toInt()
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
                val forward = httpClient.request(
                    RequestOptions().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .setMethod(method).setPort(skywalkingPort).setHost(skywalkingHost).setURI("/graphql")
                ).await()

                val selfId = request.getString("developer_id")
                val redactions = SourceStorage.getDeveloperDataRedactions(selfId)
                forward.response().onComplete { resp ->
                    resp.result().body().onComplete {
                        val respBody = it.result().toJsonObject()
                        respBody.getJsonObject("data")?.fieldNames()?.forEach {
                            val respObject = respBody.getJsonObject("data").getValue(it)
                            if (operationAliases[it] == "queryTrace" && redactions.isNotEmpty()) {
                                doQueryTraceRedaction(respObject as JsonObject, fieldAliases[it]!!, redactions)
                            }
                        }

                        log.trace { Msg.msg("Forwarding SkyWalking response: {}", respBody.toString()) }
                        val respOb = JsonObject()
                        respOb.put("status", resp.result().statusCode())
                        respOb.put("body", respBody.toString())
                        req.reply(respOb)
                    }
                }

                headers?.fieldNames()?.forEach {
                    forward.putHeader(it, headers.getValue(it).toString())
                }
                forward.end(body).await()
            }
        }

        router.route("/graphql/skywalking").handler(BodyHandler.create()).handler { req ->
            var selfId = req.user()?.principal()?.getString("developer_id")
            if (selfId == null) {
                if (System.getenv("SPP_DISABLE_JWT") != "true") {
                    req.response().setStatusCode(500).end("Missing self id")
                    return@handler
                } else {
                    selfId = "system"
                }
            }
            forwardSkyWalkingRequest(req.bodyAsString, req.request(), selfId)
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
