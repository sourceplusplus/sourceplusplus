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
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.platform.common.ClusterConnection.router
import spp.platform.common.util.args
import spp.protocol.service.LiveManagementService

/**
 * Serves the REST API.
 */
class RestAPI(private val jwtEnabled: Boolean) : CoroutineVerticle() {

    private val log = KotlinLogging.logger {}

    override suspend fun start() {
        router.get("/api/new-token").order(0).handler(this::newToken)
        router["/health"].handler(this::getHealth)
        router["/stats"].handler(this::getStats)
        router["/clients"].handler(this::getClients)
        router["/metrics"].handler(this::getMetrics)
    }

    private fun newToken(ctx: RoutingContext) {
        if (!jwtEnabled) {
            log.debug { "Skipped generating JWT token. Reason: JWT authentication disabled" }
            ctx.response().setStatusCode(202).end()
            return
        }
        val accessTokenParam = ctx.queryParam("authorization_code")
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

        val accessToken = accessTokenParam[0]
        log.debug { "Verifying access token: {}".args(accessToken) }
        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx).getAccessToken(accessToken).onSuccess {
                ctx.end(it)
            }.onFailure {
                log.warn("Invalid token request. Token: {}", accessToken)
                ctx.response().setStatusCode(401).end()
            }
        }
    }

    private fun getHealth(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info("Get platform health. Developer: {}", selfId)

        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx, accessToken).getHealth().onSuccess {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(it.toString())
            }.onFailure {
                if (it is ReplyException) {
                    log.error("Failed to get platform health. Reason: {}", it.message)
                    if (it.failureCode() < 0) {
                        ctx.response().setStatusCode(500).end(it.message)
                    } else {
                        ctx.response().setStatusCode(it.failureCode()).end(it.message)
                    }
                } else {
                    log.error("Failed to get platform health", it)
                    ctx.response().setStatusCode(500).end()
                }
            }
        }
    }

    private fun getStats(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info("Get platform stats. Developer: {}", selfId)

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

    private fun getClients(ctx: RoutingContext) {
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info { "Get platform clients. Developer: {}".args(selfId) }

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

    private fun getMetrics(ctx: RoutingContext) {
        val includeUnused = ctx.queryParam("include_unused").contains("true")
        var selfId = ctx.user()?.principal()?.getString("developer_id")
        val accessToken: String? = ctx.user()?.principal()?.getString("access_token")
        if (selfId == null) {
            if (jwtEnabled) {
                ctx.response().setStatusCode(500).end("Missing self id")
                return
            } else {
                selfId = "system"
            }
        }
        log.info("Get platform metrics. Developer: {}", selfId)

        launch(vertx.dispatcher()) {
            LiveManagementService.createProxy(vertx, accessToken).getMetrics(includeUnused).onSuccess {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(it.toString())
            }.onFailure {
                if (it is ReplyException) {
                    log.error("Failed to get platform metrics. Reason: {}", it.message)
                    if (it.failureCode() < 0) {
                        ctx.response().setStatusCode(500).end(it.message)
                    } else {
                        ctx.response().setStatusCode(it.failureCode()).end(it.message)
                    }
                } else {
                    log.error("Failed to get platform metrics", it)
                    ctx.response().setStatusCode(500).end()
                }
            }
        }
    }
}
