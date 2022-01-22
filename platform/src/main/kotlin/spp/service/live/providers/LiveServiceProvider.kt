package spp.service.live.providers

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.eventbus.impl.MessageImpl
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.impl.jose.JWT
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.ServiceDiscovery
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.joor.Reflect
import org.slf4j.LoggerFactory
import spp.platform.core.SourceStorage
import spp.platform.probe.ProbeTracker
import spp.protocol.developer.Developer
import spp.protocol.developer.SelfInfo
import spp.protocol.general.Service
import spp.protocol.platform.client.ActiveProbe
import spp.protocol.service.LiveService
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

    override fun getSelf(handler: Handler<AsyncResult<SelfInfo>>) {
        val selfId = Reflect.on(handler).get<MessageImpl<*, *>>("arg\$2").headers().let {
            if (it.contains("auth-token")) {
                JWT.parse(it.get("auth-token")).getJsonObject("payload").getString("developer_id")
            } else {
                it.get("developer_id")
            }
        }
        log.trace("Getting self info")

        GlobalScope.launch(vertx.dispatcher()) {
            handler.handle(
                Future.succeededFuture(
                    SelfInfo(
                        developer = Developer(selfId),
                        roles = SourceStorage.getDeveloperRoles(selfId),
                        permissions = SourceStorage.getDeveloperPermissions(selfId).toList(),
                        access = SourceStorage.getDeveloperAccessPermissions(selfId)
                    )
                )
            )
        }
    }

    override fun getServices(handler: Handler<AsyncResult<List<Service>>>) {
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
                handler.handle(Future.succeededFuture(result))
            } else {
                handler.handle(Future.failedFuture(it.cause()))
            }
        }
    }

    override fun getActiveProbes(handler: Handler<AsyncResult<List<ActiveProbe>>>) {
        GlobalScope.launch(vertx.dispatcher()) {
            handler.handle(Future.succeededFuture(ProbeTracker.getActiveProbes(vertx)))
        }
    }
}
