package spp.service.live.providers

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.ServiceDiscovery
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.core.SourceStorage
import spp.platform.util.RequestContext
import spp.protocol.developer.Developer
import spp.protocol.developer.SelfInfo
import spp.protocol.service.LiveService

class LiveServiceProvider(
    private val vertx: Vertx,
    private val discovery: ServiceDiscovery
) : LiveService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveServiceProvider::class.java)
    }

    override fun getSelf(handler: Handler<AsyncResult<SelfInfo>>) {
        val requestCtx = RequestContext.get()
        val selfId = requestCtx["self_id"]
        if (selfId == null) {
            handler.handle(Future.failedFuture(IllegalStateException("Missing self id")))
            return
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
}
