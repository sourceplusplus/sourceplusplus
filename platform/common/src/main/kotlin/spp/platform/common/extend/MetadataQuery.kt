/*
 * Source++, the continuous feedback platform for developers.
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
package spp.platform.common.extend

import org.apache.skywalking.oap.server.core.query.type.Service
import org.apache.skywalking.oap.server.core.query.type.ServiceInstance
import org.apache.skywalking.oap.server.core.storage.query.IMetadataQueryDAO
import org.apache.skywalking.oap.server.core.version.Version
import org.joor.Reflect

fun IMetadataQueryDAO.getMeterServices(group: String): List<Service> {
    return if (Version.CURRENT.buildVersion.startsWith("8")) {
        Reflect.on(this).call("getAllServices", group).get()
    } else if (Version.CURRENT.buildVersion.startsWith("9")) {
        Reflect.on(this).call("listServices", "", group).get()
    } else {
        error("Unsupported version: ${Version.CURRENT.name}")
    }
}

fun IMetadataQueryDAO.getMeterServiceInstances(
    startTimestamp: Long,
    endTimestamp: Long,
    serviceId: String
): List<ServiceInstance> {
    return if (Version.CURRENT.buildVersion.startsWith("8")) {
        Reflect.on(this).call("getServiceInstances", startTimestamp, endTimestamp, serviceId).get()
    } else if (Version.CURRENT.buildVersion.startsWith("9")) {
        Reflect.on(this).call("listInstances", startTimestamp, endTimestamp, serviceId).get()
    } else {
        error("Unsupported version: ${Version.CURRENT.name}")
    }
}
