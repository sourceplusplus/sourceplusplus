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
package spp.platform.core.service.cache

import com.google.common.cache.CacheBuilder
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import mu.KotlinLogging
import spp.platform.common.ClusterConnection
import spp.platform.common.DeveloperAuth
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.service.LiveManagementService
import spp.protocol.service.SourceServices
import java.util.concurrent.TimeUnit

/**
 * Intercepts the [LiveManagementService.getSelf] request and caches the result for access token lifetime.
 */
class SelfInfoCache : CoroutineVerticle() {

    private val resetCacheMethods = setOf(
        LiveManagementService::addDeveloperRole.name,
        LiveManagementService::removeDeveloperRole.name,
        LiveManagementService::addAccessPermission.name,
        LiveManagementService::removeAccessPermission.name,
        LiveManagementService::addRolePermission.name,
        LiveManagementService::removeRolePermission.name,
        LiveManagementService::addRoleAccessPermission.name,
        LiveManagementService::removeRoleAccessPermission.name
    )
    private val log = KotlinLogging.logger {}
    private val contextCacheName = "spp.cache.is-get-self"
    private val cache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build<String, SelfInfo>()

    override suspend fun start() {
        vertx.eventBus().addOutboundInterceptor<Any> {
            if (isGetSelfAction(it.message())) {
                val accessToken = it.message().headers().get("auth-token")
                val selfInfo = getCached(accessToken)
                if (selfInfo != null) {
                    log.trace { "Using cached $selfInfo for access token $accessToken" }
                    it.message().reply(selfInfo.toJson())
                } else {
                    Vertx.currentContext().putLocal(contextCacheName, true)
                    it.next()
                }
            } else {
                it.next()
            }
        }
        vertx.eventBus().addInboundInterceptor<Any> {
            if (Vertx.currentContext().removeLocal(contextCacheName)) {
                val accessToken = Vertx.currentContext().getLocal<DeveloperAuth>("developer")?.accessToken
                if (accessToken != null && it.message().body() is JsonObject) {
                    cache.put(accessToken, SelfInfo(it.message().body() as JsonObject))
                }
            } else if (isResetCacheAction(it.message())) {
                cache.invalidateAll()
                log.trace { "Invalidated cache" }
            }

            it.next()
        }
    }

    private fun isResetCacheAction(message: Message<Any?>): Boolean {
        val action = message.headers().get("action") ?: false
        return message.address() == SourceServices.LIVE_MANAGEMENT && resetCacheMethods.contains(action)
    }

    private fun isGetSelfAction(message: Message<Any?>): Boolean {
        val action = message.headers().get("action") ?: false
        return message.address() == SourceServices.LIVE_MANAGEMENT && action == LiveManagementService::getSelf.name
    }

    private fun getCached(accessToken: String?): SelfInfo? {
        val jwtConfig = ClusterConnection.config.getJsonObject("spp-platform").getJsonObject("jwt")
        if (!jwtConfig.getString("enabled").toBooleanStrict()) return null

        require(accessToken != null)
        return cache.getIfPresent(accessToken)
    }
}
