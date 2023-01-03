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
package spp.platform.common.util

import io.grpc.Context
import io.vertx.core.Vertx

object ContextUtil {
    @JvmStatic
    val CLIENT_ID = Context.key<String>("spp-platform.client-id")!!

    @JvmStatic
    val CLIENT_ACCESS = Context.key<String>("spp-platform.client-access")!!

    @JvmStatic
    val TENANT_ID = Context.key<String>("spp-platform.tenant-id")!!

    @JvmStatic
    fun addToVertx(context: Context?) {
        if (context == null) return
        val vertxContext = Vertx.currentContext() ?: return
        CLIENT_ID.get(context).let {
            if (it != null) {
                vertxContext.putLocal("client_id", it)
            } else {
                vertxContext.removeLocal("client_id")
            }
        }
        CLIENT_ACCESS.get(context).let {
            if (it != null) {
                vertxContext.putLocal("client_access", it)
            } else {
                vertxContext.removeLocal("client_access")
            }
        }
        TENANT_ID.get(context).let {
            if (it != null) {
                vertxContext.putLocal("tenant_id", it)
            } else {
                vertxContext.removeLocal("tenant_id")
            }
        }
    }
}
