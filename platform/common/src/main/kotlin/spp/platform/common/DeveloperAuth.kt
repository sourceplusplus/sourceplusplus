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

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.ClusterSerializable
import spp.platform.common.util.NoArg

@NoArg
data class DeveloperAuth(
    var selfId: String,
    var accessToken: String? = null,
) : ClusterSerializable {

    override fun toString(): String = selfId

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeveloperAuth) return false
        if (selfId != other.selfId) return false
        return true
    }

    override fun hashCode(): Int = selfId.hashCode()

    override fun writeToBuffer(buffer: Buffer) {
        buffer.appendBuffer(JsonObject.mapFrom(this).toBuffer())
    }

    override fun readFromBuffer(pos: Int, buffer: Buffer): Int {
        val json = buffer.toJsonObject()
        selfId = json.getString("selfId")
        accessToken = json.getString("accessToken")
        return pos + buffer.length()
    }

    companion object {
        fun from(jsonObject: JsonObject): DeveloperAuth {
            val selfId = jsonObject.getString("selfId")
            val accessToken = jsonObject.getString("accessToken")
            return DeveloperAuth(selfId, accessToken)
        }

        fun from(selfId: String, accessToken: String?): DeveloperAuth {
            return DeveloperAuth(selfId, accessToken)
        }
    }
}
