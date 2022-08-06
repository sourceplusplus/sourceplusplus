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
package spp.platform.core.service

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.storage.SourceStorage
import spp.protocol.platform.auth.ClientAccess
import spp.protocol.platform.auth.DeveloperRole
import spp.protocol.platform.auth.RolePermission
import spp.protocol.service.LiveManagementService

class LiveManagementServiceProvider(private val vertx: Vertx) : LiveManagementService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveManagementServiceProvider::class.java)
    }

    override fun getRolePermissions(role: String): Future<List<RolePermission>> {
        val promise = Promise.promise<List<RolePermission>>()
        GlobalScope.launch(vertx.dispatcher()) {
            try {
                val permissions = SourceStorage.getRolePermissions(DeveloperRole.fromString(role))
                promise.complete(permissions.toList())
            } catch (e: Exception) {
                log.error("Failed to get role permissions", e)
                promise.fail(e)
            }
        }
        return promise.future()
    }

    override fun getClientAccessors(): Future<List<ClientAccess>> {
        val promise = Promise.promise<List<ClientAccess>>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getClientAccessors())
        }
        return promise.future()
    }

    override fun getClientAccess(id: String): Future<ClientAccess?> {
        val promise = Promise.promise<ClientAccess?>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.getClientAccess(id))
        }
        return promise.future()
    }

    override fun addClientAccess(): Future<ClientAccess> {
        val promise = Promise.promise<ClientAccess>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.addClientAccess())
        }
        return promise.future()
    }

    override fun removeClientAccess(id: String): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.removeClientAccess(id))
        }
        return promise.future()
    }

    override fun updateClientAccess(id: String): Future<ClientAccess> {
        val promise = Promise.promise<ClientAccess>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(SourceStorage.updateClientAccess(id))
        }
        return promise.future()
    }
}
