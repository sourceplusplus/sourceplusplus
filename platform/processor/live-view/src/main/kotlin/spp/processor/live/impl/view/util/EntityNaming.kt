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
package spp.processor.live.impl.view.util

import org.apache.skywalking.oap.server.core.analysis.IDManager
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.MetricsMetaInfo
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine
import org.joor.Reflect

object EntityNaming {

    //adopted from skywalking
    fun getEntityName(meta: MetricsMetaInfo): String? {
        if (meta.metricsName?.startsWith("spp_") == true) {
            return meta.metricsName
        }

        val scope = meta.scope
        return if (DefaultScopeDefine.inServiceCatalog(scope)) {
            val serviceId = meta.id
            val serviceIDDefinition = IDManager.ServiceID.analysisId(
                serviceId
            )
            serviceIDDefinition.name
        } else if (DefaultScopeDefine.inServiceInstanceCatalog(scope)) {
            val instanceId = meta.id
            val instanceIDDefinition = IDManager.ServiceInstanceID.analysisId(
                instanceId
            )
            instanceIDDefinition.name
        } else if (DefaultScopeDefine.inEndpointCatalog(scope)) {
            val endpointId = meta.id
            val endpointIDDefinition = IDManager.EndpointID.analysisId(
                endpointId
            )
            endpointIDDefinition.endpointName
        } else if (DefaultScopeDefine.inServiceRelationCatalog(scope)) {
            val serviceRelationId = meta.id
            val serviceRelationDefine = IDManager.ServiceID.analysisRelationId(
                serviceRelationId
            )
            val sourceIdDefinition = IDManager.ServiceID.analysisId(
                serviceRelationDefine.sourceId
            )
            val destIdDefinition = IDManager.ServiceID.analysisId(
                serviceRelationDefine.destId
            )
            sourceIdDefinition.name + " to " + destIdDefinition.name
        } else if (DefaultScopeDefine.inServiceInstanceRelationCatalog(scope)) {
            val instanceRelationId = meta.id
            val serviceRelationDefine = IDManager.ServiceInstanceID.analysisRelationId(
                instanceRelationId
            )
            val sourceIdDefinition = IDManager.ServiceInstanceID.analysisId(
                serviceRelationDefine.sourceId
            )
            val sourceServiceId = IDManager.ServiceID.analysisId(
                sourceIdDefinition.serviceId
            )
            val destIdDefinition = IDManager.ServiceInstanceID.analysisId(
                serviceRelationDefine.destId
            )
            val destServiceId = IDManager.ServiceID.analysisId(
                destIdDefinition.serviceId
            )
            (sourceIdDefinition.name + " of " + sourceServiceId.name
                    + " to " + destIdDefinition.name + " of " + destServiceId.name)
        } else if (DefaultScopeDefine.inEndpointRelationCatalog(scope)) {
            val endpointRelationId = meta.id
            val endpointRelationDefine = IDManager.EndpointID.analysisRelationId(
                endpointRelationId
            )
            val sourceService = IDManager.ServiceID.analysisId(
                endpointRelationDefine.sourceServiceId
            )
            val destService = IDManager.ServiceID.analysisId(
                endpointRelationDefine.destServiceId
            )
            (endpointRelationDefine.source + " in " + sourceService.name
                    + " to " + endpointRelationDefine.dest + " in " + destService.name)
        } else if (scope == DefaultScopeDefine.ALL) {
            ""
        } else {
            null
        }
    }

    fun getServiceId(metrics: Metrics): String? {
        val fields = Reflect.on(metrics).fields()
        if (fields.containsKey("serviceId")) {
            return fields["serviceId"]?.toString()
        }
        return null
    }
}
