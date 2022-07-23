package spp.platform.dashboard

import com.google.common.io.Resources
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import spp.booster.PortalServer
import spp.platform.common.ClusterConnection.router
import spp.platform.storage.SourceStorage
import spp.platform.storage.SourceStorage.sessionHandler

class SourceDashboard : CoroutineVerticle() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    override suspend fun start() {
        router.post("/auth").handler(sessionHandler).handler(BodyHandler.create()).handler {
            val postData = it.request().params()
            val password = postData.get("password")
            log.info { "Authenticating $password" }
            if (password?.isEmpty() == true) {
                it.redirect("/login")
                return@handler
            }

            val tenantId = postData.get("tenant_id")
            if (!tenantId.isNullOrEmpty()) {
                Vertx.currentContext().put("tenant_id", tenantId)
            }
            launch(vertx.dispatcher()) {
                val dev = SourceStorage.getDeveloperByAccessToken(password)
                if (dev != null) {
                    it.session().put("developer_id", dev.id)
                    it.redirect("/")
                } else {
                    if (!tenantId.isNullOrEmpty()) {
                        it.redirect("/login?tenant_id=$tenantId")
                    } else {
                        it.redirect("/login")
                    }
                }
            }
        }
        router.get("/login").handler(sessionHandler).handler {
            val loginHtml = Resources.toString(Resources.getResource("login.html"), Charsets.UTF_8)
            it.response().putHeader("Content-Type", "text/html").end(loginHtml)
        }
        router.get("/*").handler(sessionHandler).handler { ctx ->
            if (ctx.session().get<String>("developer_id") == null) {
                ctx.redirect("/login")
                return@handler
            } else {
                ctx.next()
            }
        }
        router.post("/graphql/dashboard").handler(sessionHandler).handler(BodyHandler.create()).handler { ctx ->
            if (ctx.session().get<String>("developer_id") != null) {
                val forward = JsonObject()
                forward.put("developer_id", ctx.session().get<String>("developer_id"))
                forward.put("body", ctx.body().asJsonObject())
                val headers = JsonObject()
                ctx.request().headers().names().forEach {
                    headers.put(it, ctx.request().headers().get(it))
                }
                forward.put("headers", headers)
                forward.put("method", ctx.request().method().name())
                vertx.eventBus().request<JsonObject>("skywalking-forwarder", forward) {
                    if (it.succeeded()) {
                        val resp = it.result().body()
                        ctx.response().setStatusCode(resp.getInteger("status")).end(resp.getString("body"))
                    } else {
                        log.error("Failed to forward SkyWalking request", it.cause())
                        ctx.response().setStatusCode(500).end(it.cause().message)
                    }
                }
            } else {
                ctx.response().setStatusCode(401).end("Unauthorized")
            }
        }

        //Serve dashboard
        PortalServer.addStaticHandler(router, sessionHandler)
        PortalServer.addSPAHandler(router, sessionHandler)
    }
}
