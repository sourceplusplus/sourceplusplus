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
package application

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler

@Suppress("unused")
class KotlinVertxEndpoints : AbstractVerticle() {

    override fun start(startFuture: Promise<Void>) {
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        router.post("/debug/login-error/login").handler { login(it) }
        router.post("/debug/login-error/create-user").handler { createUser(it) }
        vertx.createHttpServer().requestHandler(router).listen()
    }

    private fun login(ctx: RoutingContext) = Unit
    private fun createUser(ctx: RoutingContext) = Unit
}
