package spp.platform.core

import graphql.GraphQL
import graphql.schema.Coercing
import graphql.schema.CoercingParseValueException
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.*
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.impl.RoutingContextImpl
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.commons.lang3.EnumUtils
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.protocol.auth.RolePermission.*
import spp.protocol.auth.error.InstrumentAccessDenied
import spp.protocol.auth.error.PermissionAccessDenied
import spp.platform.util.RequestContext
import spp.protocol.auth.*
import spp.protocol.developer.Developer
import spp.protocol.instrument.InstrumentThrottle
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.LiveSourceLocation
import spp.protocol.instrument.ThrottleStep
import spp.protocol.instrument.breakpoint.LiveBreakpoint
import spp.protocol.instrument.log.LiveLog
import spp.protocol.instrument.meter.LiveMeter
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.service.ServiceProvider
import java.util.*
import java.util.concurrent.CompletableFuture

object SourceService {

    private val log = LoggerFactory.getLogger(SourceService::class.java)

    fun setupGraphQL(vertx: Vertx): GraphQL {
        val schema = vertx.fileSystem().readFileBlocking("spp-api.graphqls").toString()
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry: TypeDefinitionRegistry = schemaParser.parse(schema)
        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .scalar(
                GraphQLScalarType.newScalar().name("Long")
                    .coercing(object : Coercing<Long, Long> {
                        override fun serialize(dataFetcherResult: Any): Long {
                            return dataFetcherResult as Long
                        }

                        override fun parseValue(input: Any): Long {
                            return when (input) {
                                is Number -> input.toLong()
                                is String -> {
                                    try {
                                        return input.toLong()
                                    } catch (e: NumberFormatException) {
                                        throw CoercingParseValueException("Invalid long value: $input")
                                    }
                                }
                                else -> throw CoercingParseValueException("Expected Number or String")
                            }
                        }

                        override fun parseLiteral(input: Any): Long {
                            return input as Long
                        }
                    }).build()
            )
            .type(TypeRuntimeWiring.newTypeWiring("LiveInstrument").typeResolver {
                if ((it.getObject() as Any) is LiveBreakpoint ||
                    (it.getObject() as Map<String, Any>)["type"] == "BREAKPOINT"
                ) {
                    it.schema.getObjectType("LiveBreakpoint")
                } else if ((it.getObject() as Any) is LiveLog ||
                    (it.getObject() as Map<String, Any>)["type"] == "LOG"
                ) {
                    it.schema.getObjectType("LiveLog")
                } else {
                    it.schema.getObjectType("LiveMeter")
                }
            }.build())
            .type(
                "Query"
            ) { builder: TypeRuntimeWiring.Builder ->
                builder.dataFetcher(
                    "getAccessPermissions",
                    this::getAccessPermissions
                ).dataFetcher(
                    "getAccessPermission",
                    this::getAccessPermission
                ).dataFetcher(
                    "getRoleAccessPermissions",
                    this::getRoleAccessPermissions
                ).dataFetcher(
                    "getDeveloperAccessPermissions",
                    this::getDeveloperAccessPermissions
                ).dataFetcher(
                    "getDataRedactions",
                    this::getDataRedactions
                ).dataFetcher(
                    "getDataRedaction",
                    this::getDataRedaction
                ).dataFetcher(
                    "getRoleDataRedactions",
                    this::getRoleDataRedactions
                ).dataFetcher(
                    "getDeveloperDataRedactions",
                    this::getDeveloperDataRedactions
                ).dataFetcher(
                    "getRoles",
                    this::getRoles
                ).dataFetcher(
                    "getRolePermissions",
                    this::getRolePermissions
                ).dataFetcher(
                    "getDeveloperRoles",
                    this::getDeveloperRoles
                ).dataFetcher(
                    "getDeveloperPermissions",
                    this::getDeveloperPermissions
                ).dataFetcher(
                    "getDevelopers",
                    this::getDevelopers
                ).dataFetcher(
                    "getLiveInstruments",
                    this::getLiveInstruments
                ).dataFetcher(
                    "getLiveBreakpoints",
                    this::getLiveBreakpoints
                ).dataFetcher(
                    "getLiveLogs",
                    this::getLiveLogs
                ).dataFetcher(
                    "getLiveMeters",
                    this::getLiveMeters
                ).dataFetcher(
                    "getSelf",
                    this::getSelf
                )
            }
            .type(
                "Mutation"
            ) { builder: TypeRuntimeWiring.Builder ->
                builder.dataFetcher(
                    "reset",
                    this::reset
                ).dataFetcher(
                    "addDataRedaction",
                    this::addDataRedaction
                ).dataFetcher(
                    "removeDataRedaction",
                    this::removeDataRedaction
                ).dataFetcher(
                    "addRoleDataRedaction",
                    this::addRoleDataRedaction
                ).dataFetcher(
                    "removeRoleDataRedaction",
                    this::removeRoleDataRedaction
                ).dataFetcher(
                    "addAccessPermission",
                    this::addAccessPermission
                ).dataFetcher(
                    "removeAccessPermission",
                    this::removeAccessPermission
                ).dataFetcher(
                    "addRoleAccessPermission",
                    this::addRoleAccessPermission
                ).dataFetcher(
                    "removeRoleAccessPermission",
                    this::removeRoleAccessPermission
                ).dataFetcher(
                    "addRole",
                    this::addRole
                ).dataFetcher(
                    "removeRole",
                    this::removeRole
                ).dataFetcher(
                    "addRolePermission",
                    this::addRolePermission
                ).dataFetcher(
                    "removeRolePermission",
                    this::removeRolePermission
                ).dataFetcher(
                    "addDeveloperRole",
                    this::addDeveloperRole
                ).dataFetcher(
                    "removeDeveloperRole",
                    this::removeDeveloperRole
                ).dataFetcher(
                    "addDeveloper",
                    this::addDeveloper
                ).dataFetcher(
                    "removeDeveloper",
                    this::removeDeveloper
                ).dataFetcher(
                    "refreshDeveloperToken",
                    this::refreshDeveloperToken
                ).dataFetcher(
                    "removeLiveInstrument",
                    this::removeLiveInstrument
                ).dataFetcher(
                    "removeLiveInstruments",
                    this::removeLiveInstruments
                ).dataFetcher(
                    "clearLiveInstruments",
                    this::clearLiveInstruments
                ).dataFetcher(
                    "addLiveBreakpoint",
                    this::addLiveBreakpoint
                ).dataFetcher(
                    "addLiveLog",
                    this::addLiveLog
                ).dataFetcher(
                    "addLiveMeter",
                    this::addLiveMeter
                )
            }.build()
        val schemaGenerator = SchemaGenerator()
        val graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    private fun getAccessPermissions(env: DataFetchingEnvironment): CompletableFuture<List<AccessPermission>> {
        val completableFuture = CompletableFuture<List<AccessPermission>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_ACCESS_PERMISSIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_ACCESS_PERMISSIONS))
                    return@launch
                }
            }
            completableFuture.complete(SourceStorage.getAccessPermissions().toList())
        }
        return completableFuture
    }

    private fun getAccessPermission(env: DataFetchingEnvironment): CompletableFuture<AccessPermission> {
        val completableFuture = CompletableFuture<AccessPermission>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_ACCESS_PERMISSIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_ACCESS_PERMISSIONS))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $id"))
            } else {
                completableFuture.complete(SourceStorage.getAccessPermission(id))
            }
        }
        return completableFuture
    }

    private fun getRoleAccessPermissions(env: DataFetchingEnvironment): CompletableFuture<List<AccessPermission>> {
        val completableFuture = CompletableFuture<List<AccessPermission>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_ACCESS_PERMISSIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_ACCESS_PERMISSIONS))
                    return@launch
                }
            }

            val role = env.getArgument<String>("role")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                val developerRole = DeveloperRole.fromString(role)
                completableFuture.complete(SourceStorage.getRoleAccessPermissions(developerRole).toList())
            }
        }
        return completableFuture
    }

    private fun getDeveloperAccessPermissions(env: DataFetchingEnvironment): CompletableFuture<List<AccessPermission>> {
        val completableFuture = CompletableFuture<List<AccessPermission>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_ACCESS_PERMISSIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_ACCESS_PERMISSIONS))
                    return@launch
                }
            }

            val developerId = env.getArgument<String>("developerId")
            if (!SourceStorage.hasDeveloper(developerId)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                completableFuture.complete(SourceStorage.getDeveloperAccessPermissions(developerId).toList())
            }
        }
        return completableFuture
    }

    private fun getDataRedactions(env: DataFetchingEnvironment): CompletableFuture<List<DataRedaction>> {
        val completableFuture = CompletableFuture<List<DataRedaction>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DATA_REDACTIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DATA_REDACTIONS))
                    return@launch
                }
            }
            completableFuture.complete(SourceStorage.getDataRedactions().toList())
        }
        return completableFuture
    }

    private fun getDataRedaction(env: DataFetchingEnvironment): CompletableFuture<DataRedaction> {
        val completableFuture = CompletableFuture<DataRedaction>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DATA_REDACTIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DATA_REDACTIONS))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                completableFuture.complete(SourceStorage.getDataRedaction(id))
            }
        }
        return completableFuture
    }

    private fun getRoleDataRedactions(env: DataFetchingEnvironment): CompletableFuture<List<DataRedaction>> {
        val completableFuture = CompletableFuture<List<DataRedaction>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DATA_REDACTIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DATA_REDACTIONS))
                    return@launch
                }
            }

            val role = env.getArgument<String>("role")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                val developerRole = DeveloperRole.fromString(role)
                completableFuture.complete(SourceStorage.getRoleDataRedactions(developerRole).toList())
            }
        }
        return completableFuture
    }

    private fun getDeveloperDataRedactions(env: DataFetchingEnvironment): CompletableFuture<List<DataRedaction>> {
        val completableFuture = CompletableFuture<List<DataRedaction>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DATA_REDACTIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DATA_REDACTIONS))
                    return@launch
                }
            }

            val developerId = env.getArgument<String>("developerId")
            if (!SourceStorage.hasDeveloper(developerId)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $developerId"))
            } else {
                completableFuture.complete(SourceStorage.getDeveloperDataRedactions(developerId).toList())
            }
        }
        return completableFuture
    }

    private fun getRoles(env: DataFetchingEnvironment): CompletableFuture<List<DeveloperRole>> {
        val completableFuture = CompletableFuture<List<DeveloperRole>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_ROLES)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_ROLES))
                    return@launch
                }
            }
            completableFuture.complete(SourceStorage.getRoles().toList())
        }
        return completableFuture
    }

    private fun getRolePermissions(env: DataFetchingEnvironment): CompletableFuture<List<RolePermission>> {
        val completableFuture = CompletableFuture<List<RolePermission>>()
        GlobalScope.launch {
            val role = env.getArgument<String>("role")
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_ROLE_PERMISSIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_ROLE_PERMISSIONS))
                    return@launch
                }
            }

            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                completableFuture.complete(SourceStorage.getRolePermissions(DeveloperRole.fromString(role)).toList())
            }
        }
        return completableFuture
    }

    private fun getDeveloperRoles(env: DataFetchingEnvironment): CompletableFuture<List<DeveloperRole>> {
        val completableFuture = CompletableFuture<List<DeveloperRole>>()
        GlobalScope.launch {
            val id = env.getArgument<String>("id").toLowerCase().replace(" ", "")
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DEVELOPER_ROLES)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DEVELOPER_ROLES))
                    return@launch
                }
            }

            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else {
                completableFuture.complete(SourceStorage.getDeveloperRoles(id))
            }
        }
        return completableFuture
    }

    private fun getDeveloperPermissions(env: DataFetchingEnvironment): CompletableFuture<List<RolePermission>> {
        val completableFuture = CompletableFuture<List<RolePermission>>()
        GlobalScope.launch {
            val id = env.getArgument<String>("id").toLowerCase().replace(" ", "")
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DEVELOPER_PERMISSIONS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DEVELOPER_PERMISSIONS))
                    return@launch
                }
            }

            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else {
                completableFuture.complete(SourceStorage.getDeveloperPermissions(id).toList())
            }
        }
        return completableFuture
    }

    private fun getDevelopers(env: DataFetchingEnvironment): CompletableFuture<List<Developer>> {
        val completableFuture = CompletableFuture<List<Developer>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, GET_DEVELOPERS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_DEVELOPERS))
                    return@launch
                }
            }

            completableFuture.complete(SourceStorage.getDevelopers())
        }
        return completableFuture
    }

    private fun getSelf(env: DataFetchingEnvironment): CompletableFuture<Map<Any, Any>> {
        val completableFuture = CompletableFuture<Map<Any, Any>>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                completableFuture.complete(mutableMapOf<Any, Any>().apply {
                    put("developer", Developer(selfId))
                    put("roles", SourceStorage.getDeveloperRoles(selfId))
                    put("permissions", SourceStorage.getDeveloperPermissions(selfId))
                    put("access", SourceStorage.getDeveloperAccessPermissions(selfId))
                })
            } else {
                completableFuture.completeExceptionally(IllegalStateException("JWT disabled"))
            }
        }
        return completableFuture
    }

    private fun refreshDeveloperToken(env: DataFetchingEnvironment): CompletableFuture<Developer> {
        val completableFuture = CompletableFuture<Developer>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REFRESH_DEVELOPER_TOKEN)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REFRESH_DEVELOPER_TOKEN))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").toLowerCase().replace(" ", "")
            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else {
                val newToken = RandomStringUtils.randomAlphanumeric(50)
                SourceStorage.setAccessToken(id, newToken)
                completableFuture.complete(Developer(id, newToken))
            }
        }
        return completableFuture
    }

    private fun getLiveInstruments(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, GET_LIVE_INSTRUMENTS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_LIVE_INSTRUMENTS))
                    return@launch
                }
                devId
            } else "system"

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.getLiveInstruments {
                if (it.succeeded()) {
                    completableFuture.complete(it.result().map { fixJsonMaps(it) })
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveBreakpoints(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, GET_LIVE_BREAKPOINTS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_LIVE_BREAKPOINTS))
                    return@launch
                }
                devId
            } else "system"

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.getLiveBreakpoints {
                if (it.succeeded()) {
                    completableFuture.complete(it.result().map { fixJsonMaps(it) })
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveLogs(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, GET_LIVE_LOGS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_LIVE_LOGS))
                    return@launch
                }
                devId
            } else "system"

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.getLiveLogs {
                if (it.succeeded()) {
                    completableFuture.complete(it.result().map { fixJsonMaps(it) })
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveMeters(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, GET_LIVE_METERS)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(GET_LIVE_METERS))
                    return@launch
                }
                devId
            } else "system"

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.getLiveMeters {
                if (it.succeeded()) {
                    completableFuture.complete(it.result().map { fixJsonMaps(it) })
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun reset(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, RESET)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(RESET))
                    return@launch
                }
                devId
            } else "system"

            RequestContext.put("self_id", selfId)
            SourceStorage.reset()
            ServiceProvider.liveProviders.liveInstrument.clearAllLiveInstruments()
            completableFuture.complete(SourceStorage.reset())
        }
        return completableFuture
    }


    private fun addDataRedaction(env: DataFetchingEnvironment): CompletableFuture<DataRedaction> {
        val completableFuture = CompletableFuture<DataRedaction>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_DATA_REDACTION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_DATA_REDACTION))
                    return@launch
                }
            }

            val redactionPattern: String = env.getArgument("redactionPattern")
            val id = UUID.randomUUID().toString()
            SourceStorage.addDataRedaction(id, redactionPattern)
            completableFuture.complete(DataRedaction(id, redactionPattern))
        }
        return completableFuture
    }

    private fun removeDataRedaction(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_DATA_REDACTION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_DATA_REDACTION))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                SourceStorage.removeDataRedaction(id)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addRoleDataRedaction(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_DATA_REDACTION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_DATA_REDACTION))
                    return@launch
                }
            }

            val role: String = env.getArgument("role")
            val id: String = env.getArgument("dataRedactionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $role"))
            } else {
                SourceStorage.addDataRedactionToRole(id, DeveloperRole.fromString(role))
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeRoleDataRedaction(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_DATA_REDACTION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_DATA_REDACTION))
                    return@launch
                }
            }

            val role: String = env.getArgument("role")
            val id: String = env.getArgument("dataRedactionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                SourceStorage.removeDataRedactionFromRole(id, DeveloperRole.fromString(role))
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addAccessPermission(env: DataFetchingEnvironment): CompletableFuture<AccessPermission> {
        val completableFuture = CompletableFuture<AccessPermission>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_ACCESS_PERMISSION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_ACCESS_PERMISSION))
                    return@launch
                }
            }

            val locationPatterns: List<String> = env.getArgument("locationPatterns")
            val type: AccessType = AccessType.valueOf(env.getArgument("type"))
            val id = UUID.randomUUID().toString()
            SourceStorage.addAccessPermission(id, locationPatterns, type)
            completableFuture.complete(AccessPermission(id, locationPatterns, type))
        }
        return completableFuture
    }

    private fun removeAccessPermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_ACCESS_PERMISSION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_ACCESS_PERMISSION))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (!SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $id"))
            } else {
                SourceStorage.removeAccessPermission(id)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addRoleAccessPermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_ACCESS_PERMISSION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_ACCESS_PERMISSION))
                    return@launch
                }
            }

            val role: String = env.getArgument("role")
            val id: String = env.getArgument("accessPermissionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $id"))
            } else {
                SourceStorage.addAccessPermissionToRole(id, DeveloperRole.fromString(role))
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeRoleAccessPermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_ACCESS_PERMISSION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_ACCESS_PERMISSION))
                    return@launch
                }
            }

            val role: String = env.getArgument("role")
            val id: String = env.getArgument("accessPermissionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $role"))
            } else {
                SourceStorage.removeAccessPermissionFromRole(id, DeveloperRole.fromString(role))
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_ROLE)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_ROLE))
                    return@launch
                }
            }

            val role: String = env.getArgument("role")
            if (SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Existing role: $role"))
            } else {
                completableFuture.complete(SourceStorage.addRole(role))
            }
        }
        return completableFuture
    }

    private fun removeRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_ROLE)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_ROLE))
                    return@launch
                }
            }

            val role: String = env.getArgument("role")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                val developerRole = DeveloperRole.fromString(role)
                if (developerRole.nativeRole) {
                    completableFuture.completeExceptionally(IllegalArgumentException("Unable to remove native role"))
                } else {
                    completableFuture.complete(SourceStorage.removeRole(developerRole))
                }
            }
        }
        return completableFuture
    }

    private fun addRolePermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_ROLE_PERMISSION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_ROLE_PERMISSION))
                    return@launch
                }
            }

            val role = env.getArgument<String>("role")
            val permission = env.getArgument<String>("permission")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                val rolePermission = EnumUtils.getEnum(RolePermission::class.java, permission)
                if (rolePermission == null) {
                    completableFuture.completeExceptionally(
                        IllegalStateException("Non-existing permission: $permission")
                    )
                } else {
                    if (DeveloperRole.fromString(role).nativeRole) {
                        completableFuture.completeExceptionally(
                            IllegalArgumentException("Unable to update native role")
                        )
                    } else {
                        SourceStorage.addPermissionToRole(DeveloperRole.fromString(role), rolePermission)
                        completableFuture.complete(true)
                    }
                }
            }
        }
        return completableFuture
    }

    private fun removeRolePermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_ROLE_PERMISSION)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_ROLE_PERMISSION))
                    return@launch
                }
            }

            val role = env.getArgument<String>("role")
            val permission = env.getArgument<String>("permission")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                val rolePermission = EnumUtils.getEnum(RolePermission::class.java, permission)
                if (rolePermission == null) {
                    completableFuture.completeExceptionally(
                        IllegalStateException("Non-existing permission: $permission")
                    )
                } else {
                    if (DeveloperRole.fromString(role).nativeRole) {
                        completableFuture.completeExceptionally(
                            IllegalArgumentException("Unable to update native role")
                        )
                    } else {
                        SourceStorage.removePermissionFromRole(DeveloperRole.fromString(role), rolePermission)
                        completableFuture.complete(true)
                    }
                }
            }
        }
        return completableFuture
    }

    private fun addDeveloperRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_DEVELOPER_ROLE)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_DEVELOPER_ROLE))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").toLowerCase().replace(" ", "")
            val role = env.getArgument<String>("role")
            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                SourceStorage.addRoleToDeveloper(id, DeveloperRole.fromString(role))
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeDeveloperRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_DEVELOPER_ROLE)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_DEVELOPER_ROLE))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").toLowerCase().replace(" ", "")
            val role = env.getArgument<String>("role")
            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                SourceStorage.removeRoleFromDeveloper(id, DeveloperRole.fromString(role))
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addDeveloper(env: DataFetchingEnvironment): CompletableFuture<Developer> {
        val completableFuture = CompletableFuture<Developer>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, ADD_DEVELOPER)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_DEVELOPER))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").toLowerCase().replace(" ", "")
            if (SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Existing developer: $id"))
            } else {
                completableFuture.complete(SourceStorage.addDeveloper(id))
            }
        }
        return completableFuture
    }

    private fun removeDeveloper(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val selfId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(selfId, REMOVE_DEVELOPER)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_DEVELOPER))
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (id == "system") {
                completableFuture.completeExceptionally(IllegalArgumentException("Unable to remove system developer"))
                return@launch
            } else if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else {
                SourceStorage.removeDeveloper(id)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeLiveInstrument(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>?> {
        val completableFuture = CompletableFuture<Map<String, Any>?>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, REMOVE_LIVE_INSTRUMENT)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_LIVE_INSTRUMENT))
                    return@launch
                }
                devId
            } else "system"

            val id: String = env.getArgument("id")
            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.removeLiveInstrument(id) {
                if (it.succeeded()) {
                    if (it.result() == null) {
                        completableFuture.complete(null)
                    } else {
                        completableFuture.complete(fixJsonMaps(it.result()!!))
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun removeLiveInstruments(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, REMOVE_LIVE_INSTRUMENT)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_LIVE_INSTRUMENT))
                    return@launch
                }
                devId
            } else "system"

            val source: String = env.getArgument("source")
            val line: Int = env.getArgument("line")

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.removeLiveInstruments(
                LiveSourceLocation(source, line)
            ) {
                if (it.succeeded()) {
                    completableFuture.complete(it.result().map { fixJsonMaps(it) })
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun clearLiveInstruments(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, REMOVE_LIVE_INSTRUMENT)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(REMOVE_LIVE_INSTRUMENT))
                    return@launch
                }
                devId
            } else "system"

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.clearLiveInstruments {
                if (it.succeeded()) {
                    completableFuture.complete(it.result())
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveBreakpoint(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, ADD_LIVE_BREAKPOINT)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_LIVE_BREAKPOINT))
                    return@launch
                }
                devId
            } else "system"

            val input = JsonObject.mapFrom(env.getArgument("input"))
            val location = input.getJsonObject("location")
            val locationSource = location.getString("source")
            val locationLine = location.getInteger("line")
            if (!SourceStorage.hasInstrumentAccess(selfId, locationSource)) {
                log.warn("Rejected developer {} unauthorized instrument access to: {}", selfId, locationSource)
                completableFuture.completeExceptionally(InstrumentAccessDenied(locationSource))
                return@launch
            }

            val condition = input.getString("condition")
            val expiresAt = input.getLong("expiresAt")
            val hitLimit = input.getInteger("hitLimit")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(locationSource, locationLine),
                    condition = condition,
                    expiresAt = expiresAt,
                    hitLimit = hitLimit ?: 1,
                    throttle = throttle,
                    meta = toJsonMap(input.getJsonArray("meta"))
                )
            ) {
                if (it.succeeded()) {
                    completableFuture.complete(fixJsonMaps(it.result()))
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveLog(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, ADD_LIVE_LOG)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_LIVE_LOG))
                    return@launch
                }
                devId
            } else "system"

            val input = JsonObject.mapFrom(env.getArgument("input"))
            val location = input.getJsonObject("location")
            val locationSource = location.getString("source")
            val locationLine = location.getInteger("line")
            if (!SourceStorage.hasInstrumentAccess(selfId, locationSource)) {
                log.warn("Rejected developer {} unauthorized instrument access to: {}", selfId, locationSource)
                completableFuture.completeExceptionally(InstrumentAccessDenied(locationSource))
                return@launch
            }

            var logArguments = input.getJsonArray("logArguments")?.list?.map { it.toString() }?.toList()
            if (logArguments == null) {
                logArguments = emptyList()
            }
            val condition = input.getString("condition")
            val expiresAt = input.getLong("expiresAt")
            val hitLimit = input.getInteger("hitLimit")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.addLiveInstrument(
                LiveLog(
                    logFormat = input.getString("logFormat"), logArguments = logArguments,
                    location = LiveSourceLocation(locationSource, locationLine),
                    condition = condition,
                    expiresAt = expiresAt,
                    hitLimit = hitLimit ?: 1,
                    throttle = throttle,
                    meta = toJsonMap(input.getJsonArray("meta"))
                )
            ) {
                if (it.succeeded()) {
                    completableFuture.complete(fixJsonMaps(it.result()))
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveMeter(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        GlobalScope.launch {
            val selfId = if (System.getenv("SPP_DISABLE_JWT") != "true") {
                val devId = env.getContext<RoutingContextImpl>().user().principal().getString("developer_id")
                if (!SourceStorage.hasPermission(devId, ADD_LIVE_METER)) {
                    completableFuture.completeExceptionally(PermissionAccessDenied(ADD_LIVE_METER))
                    return@launch
                }
                devId
            } else "system"

            val input = JsonObject.mapFrom(env.getArgument("input"))
            val location = input.getJsonObject("location")
            val locationSource = location.getString("source")
            val locationLine = location.getInteger("line")
            if (!SourceStorage.hasInstrumentAccess(selfId, locationSource)) {
                log.warn("Rejected developer {} unauthorized instrument access to: {}", selfId, locationSource)
                completableFuture.completeExceptionally(InstrumentAccessDenied(locationSource))
                return@launch
            }

            val metricValueInput = input.getJsonObject("metricValue")
            val metricValue = MetricValue(
                MetricValueType.valueOf(metricValueInput.getString("valueType")),
                metricValueInput.getString("value")
            )

            val condition = input.getString("condition")
            val expiresAt = input.getLong("expiresAt")
            val hitLimit = input.getInteger("hitLimit")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            RequestContext.put("self_id", selfId)
            ServiceProvider.liveProviders.liveInstrument.addLiveInstrument(
                LiveMeter(
                    meterName = input.getString("meterName"),
                    meterType = MeterType.valueOf(input.getString("meterType")),
                    metricValue = metricValue,
                    location = LiveSourceLocation(locationSource, locationLine),
                    condition = condition,
                    expiresAt = expiresAt,
                    hitLimit = hitLimit ?: -1,
                    throttle = throttle,
                    meta = toJsonMap(input.getJsonArray("meta"))
                )
            ) {
                if (it.succeeded()) {
                    completableFuture.complete(fixJsonMaps(it.result()))
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun toJsonMap(metaArray: JsonArray?): MutableMap<String, String> {
        val meta = mutableMapOf<String, String>()
        val metaOb = metaArray ?: JsonArray()
        for (i in 0 until metaOb.size()) {
            val metaInfoOb = metaOb.getJsonObject(i)
            meta[metaInfoOb.getString("name")] = metaInfoOb.getString("value")
        }
        return meta
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixJsonMaps(liveInstrument: LiveInstrument): Map<String, Any> {
        val rtnMap = (JsonObject.mapFrom(liveInstrument).map as Map<String, Any>).toMutableMap()
        val meta = rtnMap["meta"] as LinkedHashMap<String, String>
        rtnMap["meta"] = meta.map { mapOf("name" to it.key, "value" to it.value) }.toTypedArray()
        return rtnMap
    }
}
