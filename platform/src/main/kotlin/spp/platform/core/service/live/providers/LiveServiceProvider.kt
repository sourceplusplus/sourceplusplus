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
package spp.platform.core.service.live.providers

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.ServiceDiscovery
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.core.SourceStorage
import spp.platform.probe.ProbeTracker
import spp.processor.common.DeveloperAuth
import spp.protocol.developer.Developer
import spp.protocol.developer.SelfInfo
import spp.protocol.general.Service
import spp.protocol.service.LiveService
import spp.protocol.status.ActiveProbe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class LiveServiceProvider(
    private val vertx: Vertx,
    private val discovery: ServiceDiscovery
) : LiveService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveServiceProvider::class.java)
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
            .withZone(ZoneId.systemDefault())
    }

    override fun getSelf(): Future<SelfInfo> {
        val promise = Promise.promise<SelfInfo>()
        val selfId = Vertx.currentContext().get<DeveloperAuth>("developer").selfId
        log.trace("Getting self info")

        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(
                SelfInfo(
                    developer = Developer(selfId),
                    roles = SourceStorage.getDeveloperRoles(selfId),
                    permissions = SourceStorage.getDeveloperPermissions(selfId).toList(),
                    access = SourceStorage.getDeveloperAccessPermissions(selfId)
                )
            )
        }
        return promise.future()
    }

    override fun getServices(): Future<List<Service>> {
        val promise = Promise.promise<List<Service>>()
        val request = JsonObject()
        request.put("method", HttpMethod.POST.name())
        request.put(
            "body", JsonObject()
                .put(
                    "query", "query (\$durationStart: String!, \$durationEnd: String!, \$durationStep: Step!) {\n" +
                            "  getAllServices(duration: {start: \$durationStart, end: \$durationEnd, step: \$durationStep}) {\n" +
                            "    key: id\n" +
                            "    label: name\n" +
                            "  }\n" +
                            "}"
                )
                .put(
                    "variables", JsonObject()
                        .put("durationStart", formatter.format(Instant.now().minus(365, ChronoUnit.DAYS)))
                        .put("durationEnd", formatter.format(Instant.now()))
                        .put("durationStep", "MINUTE")
                )
        )

        vertx.eventBus().request<JsonObject>("skywalking-forwarder", request) {
            if (it.succeeded()) {
                val response = it.result().body()
                val body = JsonObject(response.getString("body"))
                val data = body.getJsonObject("data")
                val services = data.getJsonArray("getAllServices")
                val result = mutableListOf<Service>()
                for (i in 0 until services.size()) {
                    val service = services.getJsonObject(i)
                    result.add(
                        Service(
                            id = service.getString("key"),
                            name = service.getString("label")
                        )
                    )
                }
                promise.complete(result)
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    override fun getActiveProbes(): Future<List<ActiveProbe>> {
        val promise = Promise.promise<List<ActiveProbe>>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(ProbeTracker.getActiveProbes(vertx))
        }
        return promise.future()
    }
}
