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
package spp.platform.common

import io.vertx.core.json.JsonObject
import spp.protocol.platform.auth.ClientAccess

data class ClientAuth(
    val access: ClientAccess,
    val tenantId: String? = null
) {
    companion object {
        fun from(jsonObject: JsonObject): ClientAuth {
            return ClientAuth(
                access = ClientAccess(
                    jsonObject.getJsonObject("access").getString("id"),
                    jsonObject.getJsonObject("access").getString("secret")
                ),
                tenantId = jsonObject.getString("tenantId")
            )
        }

        fun from(jsonString: String): ClientAuth {
            return from(JsonObject(jsonString))
        }
    }
}
