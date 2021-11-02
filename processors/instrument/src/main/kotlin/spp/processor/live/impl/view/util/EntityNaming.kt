package spp.processor.live.impl.view.util

import org.apache.skywalking.oap.server.core.analysis.IDManager
import org.apache.skywalking.oap.server.core.analysis.metrics.MetricsMetaInfo
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine

object EntityNaming {

    //taken from skywalking
    fun getEntityName(meta: MetricsMetaInfo): String? {
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
}
