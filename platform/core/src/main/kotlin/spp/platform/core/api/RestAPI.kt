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
package spp.platform.core.api

import io.vertx.core.Vertx
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.dropwizard.MetricsService
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.HealthChecks
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.common.ClusterConnection
import spp.platform.common.ClusterConnection.router
import spp.platform.common.util.args
import spp.platform.storage.SourceStorage
import spp.protocol.service.LiveManagementService
import spp.protocol.service.SourceServices
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Serves the REST API.
 */
class RestAPI(private val jwtEnabled: Boolean, private val jwt: JWTAuth?) : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}

    override suspend fun start() {
        router.get("/api/new-token").order(0).handler(this::newToken)

        //Health checks
        val healthChecks = HealthChecks.create(vertx)
        addServiceCheck(healthChecks, SourceServices.LIVE_MANAGEMENT)
        addServiceCheck(healthChecks, SourceServices.LIVE_INSTRUMENT)
        addServiceCheck(healthChecks, SourceServices.LIVE_VIEW)
        router["/health"].handler(HealthCheckHandler.createWithHealthChecks(healthChecks))
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)

        //Internal metrics
        val metricsService = MetricsService.create(vertx)
        router["/metrics"].handler {
            if (it.queryParam("include_unused").contains("true")) {
                val vertxMetrics = metricsService.getMetricsSnapshot("vertx")
                it.end(vertxMetrics.encodePrettily())
            } else {
                val rtnMetrics = JsonObject()
                val vertxMetrics = metricsService.getMetricsSnapshot("vertx")
                vertxMetrics.fieldNames().forEach {
                    val metric = vertxMetrics.getJsonObject(it)
                    val allZeros = metric.fieldNames().all {
                        if (metric.getValue(it) is Number && (metric.getValue(it) as Number).toDouble() == 0.0) {
                            true
                        } else metric.getValue(it) !is Number
                    }
                    if (!allZeros) {
                        rtnMetrics.put(it, metric)
                    }
                }
                it.end(rtnMetrics.encodePrettily())
            }
        }
    }

    private fun newToken(ctx: RoutingContext) {
        if (!jwtEnabled) {
            log.debug { "Skipped generating JWT token. Reason: JWT authentication disabled" }
            ctx.response().setStatusCode(202).end()
            return
        }
        val accessTokenParam = ctx.queryParam("access_token")
        if (accessTokenParam.isEmpty()) {
            log.warn("Invalid token request. Missing token.")
            ctx.response().setStatusCode(401).end()
            return
        }
        val tenantId = ctx.queryParam("tenant_id").firstOrNull() ?: ctx.request().headers().get("spp-tenant-id")
        if (!tenantId.isNullOrEmpty()) {
            Vertx.currentContext().putLocal("tenant_id", tenantId)
        } else {
            Vertx.currentContext().removeLocal("tenant_id")
        }

        val token = accessTokenParam[0]
        log.debug { "Verifying access token: {}".args(token) }
        launch(vertx.dispatcher()) {
            val dev = SourceStorage.getDeveloperByAccessToken(token)
            if (dev != null) {
                val jwtToken = jwt!!.generateToken(
                    JsonObject()
                        .apply {
                            if (!tenantId.isNullOrEmpty()) {
                                put("tenant_id", tenantId)
                            }
                        }
                        .put("developer_id", dev.id)
                        .put("created_at", Instant.now().toEpochMilli())
                        //todo: reasonable expires_at
                        .put("expires_at", Instant.now().plus(365, ChronoUnit.DAYS).toEpochMilli()),
                    JWTOptions().setAlgorithm("RS256")
                )
                ctx.end(jwtToken)
            } else {
                log.warn("Invalid token request. Token: {}", token)
                ctx.response().setStatusCode(401).end()
            }
        }
    }

    private fun getClients(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
            val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.debug { "Get platform clients request. Developer: {}".args(selfId) }

        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx, accessToken).getClients().onSuccess {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(it.toString())
            }.onFailure {
                if (it is ReplyException) {
                    log.error("Failed to get platform clients. Reason: {}", it.message)
                    if (it.failureCode() < 0) {
                        ctx.response().setStatusCode(500).end(it.message)
                    } else {
                        ctx.response().setStatusCode(it.failureCode()).end(it.message)
                    }
                } else {
                    log.error("Failed to get platform clients", it)
                    ctx.response().setStatusCode(500).end()
                }
            }
        }
    }

    private fun getStats(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            val jwtConfig = config.getJsonObject("spp-platform").getJsonObject("jwt")
            val jwtEnabled = jwtConfig.getString("enabled").toBooleanStrict()
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info("Get platform stats request. Developer: {}", selfId)

        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx, accessToken).getStats().onSuccess {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(it.toString())
            }.onFailure {
                if (it is ReplyException) {
                    log.error("Failed to get platform stats. Reason: {}", it.message)
                    if (it.failureCode() < 0) {
                        ctx.response().setStatusCode(500).end(it.message)
                    } else {
                        ctx.response().setStatusCode(it.failureCode()).end(it.message)
                    }
                } else {
                    log.error("Failed to get platform stats", it)
                    ctx.response().setStatusCode(500).end()
                }
            }
        }
    }

    private fun addServiceCheck(checks: HealthChecks, serviceName: String) {
        val registeredName = "services/${serviceName.substringAfterLast(".")}"
        checks.register(registeredName) { promise ->
            ClusterConnection.discovery.getRecord({ rec -> serviceName == rec.name }
            ) { record ->
                when {
                    record.failed() -> promise.fail(record.cause())
                    record.result() == null -> {
                        val debugData = JsonObject().put("reason", "No published record(s)")
                        promise.complete(Status.KO(debugData))
                    }

                    else -> {
                        val reference = ClusterConnection.discovery.getReference(record.result())
                        try {
                            reference.get<Any>()
                            promise.complete(Status.OK())
                        } catch (ex: Throwable) {
                            ex.printStackTrace()
                            val debugData = JsonObject().put("reason", ex.message)
                                .put("stack_trace", ex.stackTraceToString())
                            promise.complete(Status.KO(debugData))
                        } finally {
                            reference.release()
                        }
                    }
                }
            }
        }
    }
}
