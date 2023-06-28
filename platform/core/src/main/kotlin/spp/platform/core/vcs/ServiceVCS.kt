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
package spp.platform.core.vcs

import com.google.protobuf.Message
import org.apache.skywalking.apm.network.language.agent.v3.MeterData
import spp.platform.common.util.ContextUtil

object ServiceVCS {

    fun getServiceName(message: Message): String {
        if (message.descriptorForType.findFieldByName("service") != null) {
            val service = message.getField(message.descriptorForType.findFieldByName("service")).toString()
            if (service.isEmpty() && message is MeterData) {
                return "" // not all MeterData messages have a service name
            }
            require(service.isNotEmpty()) { "Message ${message.descriptorForType} does not have a service name" }

            return service + getEnvironment() + getCommitId()
        } else if (message.descriptorForType.findFieldByName("source") != null) {
            val source = message.getField(message.descriptorForType.findFieldByName("source"))
            if (source is Message) {
                val service = source.getField(source.descriptorForType.findFieldByName("service")).toString()
                require(service.isNotEmpty()) { "Message ${message.descriptorForType} does not have a service name" }

                return service + getEnvironment() + getCommitId()
            }
        }

        throw IllegalArgumentException("Message " + message.descriptorForType + " does not have a service name")
    }

    private fun getEnvironment(): String {
        val env = ContextUtil.ENVIRONMENT.get()
        if (env.isNullOrEmpty()) return ""
        return "|$env"
    }

    private fun getCommitId(): String {
        val commitId = ContextUtil.COMMIT_ID.get()
        if (commitId.isNullOrEmpty()) return ""
        return "|$commitId"
    }
}
