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
package spp.platform.core

import graphql.ExceptionWhileDataFetching
import graphql.GraphQL
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.schema.Coercing
import graphql.schema.CoercingParseValueException
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.*
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.servicediscovery.types.EventBusService
import kotlinx.coroutines.launch
import org.apache.commons.lang3.EnumUtils
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.DeveloperAuth
import spp.platform.storage.SourceStorage
import spp.protocol.artifact.ArtifactQualifiedName
import spp.protocol.artifact.ArtifactType
import spp.protocol.artifact.metrics.MetricStep
import spp.protocol.instrument.*
import spp.protocol.instrument.LiveInstrumentType.*
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.instrument.throttle.InstrumentThrottle
import spp.protocol.instrument.throttle.ThrottleStep
import spp.protocol.instrument.variable.LiveVariableControl
import spp.protocol.platform.auth.*
import spp.protocol.platform.auth.RolePermission.*
import spp.protocol.platform.developer.Developer
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.platform.general.Service
import spp.protocol.platform.general.ServiceEndpoint
import spp.protocol.platform.general.ServiceInstance
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import spp.protocol.service.error.InstrumentAccessDenied
import spp.protocol.view.HistoricalView
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.properties.Delegates

//todo: move validation logic to LiveManagementServiceProvider
class SourceService(private val router: Router) : CoroutineVerticle() {

    private val log = LoggerFactory.getLogger(SourceService::class.java)
    private var jwtEnabled by Delegates.notNull<Boolean>()

    override suspend fun start() {
        jwtEnabled = config.getJsonObject("jwt").getString("enabled").toBooleanStrict()

        val graphql = vertx.executeBlocking<GraphQL> {
            it.complete(setupGraphQL())
        }.await()
        val sppGraphQLHandler = GraphQLHandler.create(graphql)
        router.post("/graphql").handler(BodyHandler.create()).handler {
            if (it.request().getHeader("spp-platform-request") == "true") {
                sppGraphQLHandler.handle(it)
            } else {
                it.reroute("/graphql/skywalking")
            }
        }
        router.post("/graphql/spp").handler(BodyHandler.create()).handler {
            if (it.user() != null && Vertx.currentContext().getLocal<DeveloperAuth>("developer") == null) {
                Vertx.currentContext().putLocal(
                    "developer",
                    DeveloperAuth(
                        it.user().principal().getString("developer_id"),
                        it.user().principal().getString("access_token")
                    )
                )
            }
            sppGraphQLHandler.handle(it)
        }
    }

    private fun setupGraphQL(): GraphQL {
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
                } else if ((it.getObject() as Any) is LiveMeter ||
                    (it.getObject() as Map<String, Any>)["type"] == "METER"
                ) {
                    it.schema.getObjectType("LiveMeter")
                } else {
                    it.schema.getObjectType("LiveSpan")
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
                    "getLiveSpans",
                    this::getLiveSpans
                ).dataFetcher(
                    "getSelf",
                    this::getSelf
                ).dataFetcher(
                    "getServices",
                    this::getServices
                ).dataFetcher(
                    "getInstances",
                    this::getInstances
                ).dataFetcher(
                    "getEndpoints",
                    this::getEndpoints
                ).dataFetcher(
                    "getLiveViews",
                    this::getLiveViews
                ).dataFetcher(
                    "getHistoricalMetrics",
                    this::getHistoricalMetrics
                ).dataFetcher(
                    "getClientAccessors",
                    this::getClientAccessors
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
                    "updateDataRedaction",
                    this::updateDataRedaction
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
                ).dataFetcher(
                    "addLiveSpan",
                    this::addLiveSpan
                ).dataFetcher(
                    "addLiveView",
                    this::addLiveView
                ).dataFetcher(
                    "clearLiveViews",
                    this::clearLiveViews
                ).dataFetcher(
                    "addClientAccess",
                    this::addClientAccess
                ).dataFetcher(
                    "removeClientAccess",
                    this::removeClientAccess
                ).dataFetcher(
                    "refreshClientAccess",
                    this::refreshClientAccess
                )
            }.build()
        val schemaGenerator = SchemaGenerator()
        val graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
        return GraphQL.newGraphQL(graphQLSchema)
            .defaultDataFetcherExceptionHandler(object : DataFetcherExceptionHandler {
                override fun handleException(
                    handlerParameters: DataFetcherExceptionHandlerParameters
                ): CompletableFuture<DataFetcherExceptionHandlerResult> {
                    val exception = handlerParameters.exception
                    exception.message?.let { log.warn(it) }
                    val sourceLocation = handlerParameters.sourceLocation
                    val path = handlerParameters.path
                    val error = ExceptionWhileDataFetching(path, exception, sourceLocation)
                    return CompletableFuture.completedFuture(
                        DataFetcherExceptionHandlerResult.newResult().error(error).build()
                    )
                }
            })
            .build()
    }

    private fun getAccessPermissions(env: DataFetchingEnvironment): CompletableFuture<List<AccessPermission>> {
        val completableFuture = CompletableFuture<List<AccessPermission>>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_ACCESS_PERMISSIONS, completableFuture)) {
                    return@launch
                }
            }
            completableFuture.complete(SourceStorage.getAccessPermissions().toList())
        }
        return completableFuture
    }

    private fun getAccessPermission(env: DataFetchingEnvironment): CompletableFuture<AccessPermission> {
        val completableFuture = CompletableFuture<AccessPermission>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_ACCESS_PERMISSIONS, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (!SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $id"))
            } else {
                completableFuture.complete(SourceStorage.getAccessPermission(id))
            }
        }
        return completableFuture
    }

    private fun getRoleAccessPermissions(env: DataFetchingEnvironment): CompletableFuture<List<AccessPermission>> {
        val completableFuture = CompletableFuture<List<AccessPermission>>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_ACCESS_PERMISSIONS, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                completableFuture.complete(SourceStorage.getRoleAccessPermissions(role).toList())
            }
        }
        return completableFuture
    }

    private fun getDeveloperAccessPermissions(env: DataFetchingEnvironment): CompletableFuture<List<AccessPermission>> {
        val completableFuture = CompletableFuture<List<AccessPermission>>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_ACCESS_PERMISSIONS, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DATA_REDACTIONS, completableFuture)) {
                    return@launch
                }
            }
            completableFuture.complete(SourceStorage.getDataRedactions().toList())
        }
        return completableFuture
    }

    private fun getDataRedaction(env: DataFetchingEnvironment): CompletableFuture<DataRedaction> {
        val completableFuture = CompletableFuture<DataRedaction>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DATA_REDACTIONS, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                completableFuture.complete(SourceStorage.getDataRedaction(id))
            }
        }
        return completableFuture
    }

    private fun getRoleDataRedactions(env: DataFetchingEnvironment): CompletableFuture<List<DataRedaction>> {
        val completableFuture = CompletableFuture<List<DataRedaction>>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DATA_REDACTIONS, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                completableFuture.complete(SourceStorage.getRoleDataRedactions(role).toList())
            }
        }
        return completableFuture
    }

    private fun getDeveloperDataRedactions(env: DataFetchingEnvironment): CompletableFuture<List<DataRedaction>> {
        val completableFuture = CompletableFuture<List<DataRedaction>>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DATA_REDACTIONS, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_ROLES, completableFuture)) {
                    return@launch
                }
            }
            completableFuture.complete(SourceStorage.getRoles().toList())
        }
        return completableFuture
    }

    private fun getRolePermissions(env: DataFetchingEnvironment): CompletableFuture<List<RolePermission>> {
        val completableFuture = CompletableFuture<List<RolePermission>>()
        launch(vertx.dispatcher()) {
            val role = DeveloperRole.fromString(env.getArgument("role"))
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_ROLE_PERMISSIONS, completableFuture)) {
                    return@launch
                }
            }

            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                completableFuture.complete(SourceStorage.getRolePermissions(role).toList())
            }
        }
        return completableFuture
    }

    private fun getDeveloperRoles(env: DataFetchingEnvironment): CompletableFuture<List<DeveloperRole>> {
        val completableFuture = CompletableFuture<List<DeveloperRole>>()
        launch(vertx.dispatcher()) {
            val id = env.getArgument<String>("id").lowercase().replace(" ", "")
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DEVELOPER_ROLES, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            val id = env.getArgument<String>("id").lowercase().replace(" ", "")
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DEVELOPER_PERMISSIONS, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_DEVELOPERS, completableFuture)) {
                    return@launch
                }
            }

            completableFuture.complete(SourceStorage.getDevelopers())
        }
        return completableFuture
    }

    private fun getSelf(env: DataFetchingEnvironment): CompletableFuture<SelfInfo> {
        val completableFuture = CompletableFuture<SelfInfo>()
        var accessToken: String? = null
        if (jwtEnabled) {
            val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
            accessToken = user.principal().getString("access_token")
        }

        EventBusService.getProxy(
            discovery, LiveManagementService::class.java,
            JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
        ) {
            if (it.succeeded()) {
                it.result().getSelf().onComplete {
                    if (it.succeeded()) {
                        completableFuture.complete(it.result())
                    } else {
                        completableFuture.completeExceptionally(it.cause())
                    }
                }
            } else {
                completableFuture.completeExceptionally(it.cause())
            }
        }
        return completableFuture
    }

    private fun getServices(env: DataFetchingEnvironment): CompletableFuture<List<Service>> {
        val completableFuture = CompletableFuture<List<Service>>()
        var accessToken: String? = null
        if (jwtEnabled) {
            val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
            accessToken = user.principal().getString("access_token")
        }

        EventBusService.getProxy(
            discovery, LiveManagementService::class.java,
            JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
        ) {
            if (it.succeeded()) {
                it.result().getServices().onComplete {
                    if (it.succeeded()) {
                        completableFuture.complete(it.result())
                    } else {
                        completableFuture.completeExceptionally(it.cause())
                    }
                }
            } else {
                completableFuture.completeExceptionally(it.cause())
            }
        }
        return completableFuture
    }

    private fun getInstances(env: DataFetchingEnvironment): CompletableFuture<List<ServiceInstance>> {
        val completableFuture = CompletableFuture<List<ServiceInstance>>()
        var accessToken: String? = null
        if (jwtEnabled) {
            val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
            accessToken = user.principal().getString("access_token")
        }

        val id = env.getArgument<String>("serviceId")
        EventBusService.getProxy(
            discovery, LiveManagementService::class.java,
            JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
        ) {
            if (it.succeeded()) {
                it.result().getInstances(id).onComplete {
                    if (it.succeeded()) {
                        completableFuture.complete(it.result())
                    } else {
                        completableFuture.completeExceptionally(it.cause())
                    }
                }
            } else {
                completableFuture.completeExceptionally(it.cause())
            }
        }
        return completableFuture
    }

    private fun getEndpoints(env: DataFetchingEnvironment): CompletableFuture<List<ServiceEndpoint>> {
        val completableFuture = CompletableFuture<List<ServiceEndpoint>>()
        var accessToken: String? = null
        if (jwtEnabled) {
            val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
            accessToken = user.principal().getString("access_token")
        }

        val id = env.getArgument<String>("serviceId")
        EventBusService.getProxy(
            discovery, LiveManagementService::class.java,
            JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
        ) {
            if (it.succeeded()) {
                it.result().getEndpoints(id).onComplete {
                    if (it.succeeded()) {
                        completableFuture.complete(it.result())
                    } else {
                        completableFuture.completeExceptionally(it.cause())
                    }
                }
            } else {
                completableFuture.completeExceptionally(it.cause())
            }
        }
        return completableFuture
    }

    private fun refreshDeveloperToken(env: DataFetchingEnvironment): CompletableFuture<Developer> {
        val completableFuture = CompletableFuture<Developer>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REFRESH_DEVELOPER_TOKEN, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").lowercase().replace(" ", "")
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
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_INSTRUMENTS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result().map { fixJsonMaps(it) })
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveBreakpoints(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_BREAKPOINTS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveInstruments(BREAKPOINT).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result().map { fixJsonMaps(it) })
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveLogs(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_LOGS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveInstruments(LOG).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result().map { fixJsonMaps(it) })
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveMeters(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_METERS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveInstruments(METER).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result().map { fixJsonMaps(it) })
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveSpans(env: DataFetchingEnvironment): CompletableFuture<List<Map<String, Any>>> {
        val completableFuture = CompletableFuture<List<Map<String, Any>>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_SPANS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveInstruments(SPAN).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result().map { fixJsonMaps(it) })
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun reset(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, RESET, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            SourceStorage.reset()
            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().clearAllLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(true)
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addDataRedaction(env: DataFetchingEnvironment): CompletableFuture<DataRedaction> {
        val completableFuture = CompletableFuture<DataRedaction>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, UPDATE_DATA_REDACTION, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id") ?: UUID.randomUUID().toString()
            val type: RedactionType = RedactionType.valueOf(env.getArgument("type"))
            val lookup: String = env.getArgument("lookup")
            val replacement: String = env.getArgument("replacement")
            SourceStorage.addDataRedaction(id, type, lookup, replacement)
            completableFuture.complete(DataRedaction(id, type, lookup, replacement))
        }
        return completableFuture
    }

    private fun updateDataRedaction(env: DataFetchingEnvironment): CompletableFuture<DataRedaction> {
        val completableFuture = CompletableFuture<DataRedaction>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, UPDATE_DATA_REDACTION, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id")
            if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                val type: RedactionType? = env.getArgument<String?>("type")?.let { RedactionType.valueOf(it) }
                val lookup: String? = env.getArgument("lookup")
                val replacement: String? = env.getArgument("replacement")
                SourceStorage.updateDataRedaction(id, type, lookup, replacement)
                completableFuture.complete(SourceStorage.getDataRedaction(id))
            }
        }
        return completableFuture
    }

    private fun removeDataRedaction(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, UPDATE_DATA_REDACTION, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, UPDATE_DATA_REDACTION, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            val id: String = env.getArgument("dataRedactionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $role"))
            } else {
                SourceStorage.addDataRedactionToRole(id, role)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeRoleDataRedaction(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, UPDATE_DATA_REDACTION, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            val id: String = env.getArgument("dataRedactionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasDataRedaction(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing data redaction: $id"))
            } else {
                SourceStorage.removeDataRedactionFromRole(id, role)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addAccessPermission(env: DataFetchingEnvironment): CompletableFuture<AccessPermission> {
        val completableFuture = CompletableFuture<AccessPermission>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_ACCESS_PERMISSION, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_ACCESS_PERMISSION, completableFuture)) {
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_ACCESS_PERMISSION, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            val id: String = env.getArgument("accessPermissionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $id"))
            } else {
                SourceStorage.addAccessPermissionToRole(id, role)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeRoleAccessPermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_ACCESS_PERMISSION, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            val id: String = env.getArgument("accessPermissionId")
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else if (!SourceStorage.hasAccessPermission(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing access permission: $role"))
            } else {
                SourceStorage.removeAccessPermissionFromRole(id, role)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_ROLE, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_ROLE, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
            if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                if (role.nativeRole) {
                    completableFuture.completeExceptionally(IllegalArgumentException("Unable to remove native role"))
                } else {
                    completableFuture.complete(SourceStorage.removeRole(role))
                }
            }
        }
        return completableFuture
    }

    private fun addRolePermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_ROLE_PERMISSION, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
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
                    if (role.nativeRole) {
                        completableFuture.completeExceptionally(
                            IllegalArgumentException("Unable to update native role")
                        )
                    } else {
                        SourceStorage.addPermissionToRole(role, rolePermission)
                        completableFuture.complete(true)
                    }
                }
            }
        }
        return completableFuture
    }

    private fun removeRolePermission(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_ROLE_PERMISSION, completableFuture)) {
                    return@launch
                }
            }

            val role = DeveloperRole.fromString(env.getArgument("role"))
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
                    if (role.nativeRole) {
                        completableFuture.completeExceptionally(
                            IllegalArgumentException("Unable to update native role")
                        )
                    } else {
                        SourceStorage.removePermissionFromRole(role, rolePermission)
                        completableFuture.complete(true)
                    }
                }
            }
        }
        return completableFuture
    }

    private fun addDeveloperRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_DEVELOPER_ROLE, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").lowercase().replace(" ", "")
            val role = DeveloperRole.fromString(env.getArgument("role"))
            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                SourceStorage.addRoleToDeveloper(id, role)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun removeDeveloperRole(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_DEVELOPER_ROLE, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").lowercase().replace(" ", "")
            val role = DeveloperRole.fromString(env.getArgument("role"))
            if (!SourceStorage.hasDeveloper(id)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing developer: $id"))
            } else if (!SourceStorage.hasRole(role)) {
                completableFuture.completeExceptionally(IllegalStateException("Non-existing role: $role"))
            } else {
                SourceStorage.removeRoleFromDeveloper(id, role)
                completableFuture.complete(true)
            }
        }
        return completableFuture
    }

    private fun addDeveloper(env: DataFetchingEnvironment): CompletableFuture<Developer> {
        val completableFuture = CompletableFuture<Developer>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_DEVELOPER, completableFuture)) {
                    return@launch
                }
            }

            val id = env.getArgument<String>("id").lowercase().replace(" ", "")
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
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_DEVELOPER, completableFuture)) {
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
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_LIVE_INSTRUMENT, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            val id: String = env.getArgument("id")
            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().removeLiveInstrument(id).onComplete {
                        if (it.succeeded() && it.result() != null) {
                            completableFuture.complete(fixJsonMaps(it.result()!!))
                        } else if (it.succeeded() && it.result() == null) {
                            completableFuture.complete(null)
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
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
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_LIVE_INSTRUMENT, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            val source: String = env.getArgument("source")
            val line: Int = env.getArgument("line")

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().removeLiveInstruments(LiveSourceLocation(source, line)).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result().map { fixJsonMaps(it) })
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun clearLiveInstruments(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_LIVE_INSTRUMENT, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().clearLiveInstruments(null).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result())
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveBreakpoint(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            val selfId = if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_LIVE_BREAKPOINT, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
                devId
            } else "system"

            val input = JsonObject.mapFrom(env.getArgument("input"))
            val variableControl = input.getJsonObject("variableControl")
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
            val applyImmediately = input.getBoolean("applyImmediately")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().addLiveInstrument(
                        LiveBreakpoint(
                            variableControl = variableControl?.let { LiveVariableControl(it) },
                            location = LiveSourceLocation(locationSource, locationLine),
                            condition = condition,
                            expiresAt = expiresAt,
                            hitLimit = hitLimit ?: 1,
                            applyImmediately = applyImmediately ?: false,
                            throttle = throttle,
                            meta = toJsonMap(input.getJsonArray("meta"))
                        )
                    ).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(fixJsonMaps(it.result()))
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveLog(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            val selfId = if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_LIVE_LOG, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
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
            val applyImmediately = input.getBoolean("applyImmediately")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().addLiveInstrument(
                        LiveLog(
                            logFormat = input.getString("logFormat"), logArguments = logArguments,
                            location = LiveSourceLocation(locationSource, locationLine),
                            condition = condition,
                            expiresAt = expiresAt,
                            hitLimit = hitLimit ?: 1,
                            applyImmediately = applyImmediately ?: false,
                            throttle = throttle,
                            meta = toJsonMap(input.getJsonArray("meta"))
                        )
                    ).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(fixJsonMaps(it.result()))
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveMeter(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            val selfId = if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_LIVE_METER, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
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

            val id: String? = input.getString("id")
            val condition = input.getString("condition")
            val expiresAt = input.getLong("expiresAt")
            val hitLimit = input.getInteger("hitLimit")
            val applyImmediately = input.getBoolean("applyImmediately")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().addLiveInstrument(
                        LiveMeter(
                            meterType = MeterType.valueOf(input.getString("meterType")),
                            metricValue = metricValue,
                            id = id,
                            location = LiveSourceLocation(locationSource, locationLine),
                            condition = condition,
                            expiresAt = expiresAt,
                            hitLimit = hitLimit ?: -1,
                            applyImmediately = applyImmediately ?: false,
                            throttle = throttle,
                            meta = toJsonMap(input.getJsonArray("meta"))
                        )
                    ).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(fixJsonMaps(it.result()))
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveSpan(env: DataFetchingEnvironment): CompletableFuture<Map<String, Any>> {
        val completableFuture = CompletableFuture<Map<String, Any>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            val selfId = if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_LIVE_SPAN, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
                devId
            } else "system"

            val input = JsonObject.mapFrom(env.getArgument("input"))
            val operationName = input.getString("operationName")
            val location = input.getJsonObject("location")
            val locationSource = location.getString("source")
            val locationLine = -1 //location.getInteger("line")
            if (!SourceStorage.hasInstrumentAccess(selfId, locationSource)) {
                log.warn("Rejected developer {} unauthorized instrument access to: {}", selfId, locationSource)
                completableFuture.completeExceptionally(InstrumentAccessDenied(locationSource))
                return@launch
            }

            val condition = input.getString("condition")
            val expiresAt = input.getLong("expiresAt")
            val hitLimit = input.getInteger("hitLimit")
            val applyImmediately = input.getBoolean("applyImmediately")
            val throttleOb = input.getJsonObject("throttle")
            val throttle = if (throttleOb != null) {
                InstrumentThrottle(
                    throttleOb.getInteger("limit"),
                    ThrottleStep.valueOf(throttleOb.getString("step"))
                )
            } else InstrumentThrottle.DEFAULT

            EventBusService.getProxy(
                discovery, LiveInstrumentService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().addLiveInstrument(
                        LiveSpan(
                            operationName = operationName,
                            location = LiveSourceLocation(locationSource, locationLine),
                            condition = condition,
                            expiresAt = expiresAt,
                            hitLimit = hitLimit ?: -1,
                            applyImmediately = applyImmediately ?: false,
                            throttle = throttle,
                            meta = toJsonMap(input.getJsonArray("meta"))
                        )
                    ).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(fixJsonMaps(it.result()))
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun addLiveView(env: DataFetchingEnvironment): CompletableFuture<LiveView> {
        val completableFuture = CompletableFuture<LiveView>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_LIVE_VIEW_SUBSCRIPTION, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            val input = JsonObject.mapFrom(env.getArgument("input"))
            val viewConfig = LiveViewConfig(
                input.getJsonObject("viewConfig").getString("viewName"),
                input.getJsonObject("viewConfig").getJsonArray("viewMetrics").map { it.toString() },
                input.getJsonObject("viewConfig").getInteger("refreshRateLimit") ?: -1,
            )
            val subscription = LiveView(
                entityIds = input.getJsonArray("entityIds").list.map { it as String }.toMutableSet(),
                artifactQualifiedName = ArtifactQualifiedName("todo", type = ArtifactType.CLASS),
                artifactLocation = LiveSourceLocation("todo", -1),
                viewConfig = viewConfig
            )

            EventBusService.getProxy(
                discovery, LiveViewService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().addLiveView(subscription).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result())
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getLiveViews(env: DataFetchingEnvironment): CompletableFuture<List<LiveView>> {
        val completableFuture = CompletableFuture<List<LiveView>>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_VIEW_SUBSCRIPTIONS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveViewService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getLiveViews().onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result())
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun clearLiveViews(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_LIVE_VIEW_SUBSCRIPTION, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            EventBusService.getProxy(
                discovery, LiveViewService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().clearLiveViews().onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(true)
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getHistoricalMetrics(env: DataFetchingEnvironment): CompletableFuture<HistoricalView> {
        val completableFuture = CompletableFuture<HistoricalView>()
        var accessToken: String? = null
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
                val devId = user.principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_LIVE_VIEW_SUBSCRIPTIONS, completableFuture)) {
                    return@launch
                }
                accessToken = user.principal().getString("access_token")
            }

            val vars = JsonObject.mapFrom(env.variables)
            val entityIds = vars.getJsonArray("entityIds", JsonArray()).list.map { it as String }
            val metricIds = vars.getJsonArray("metricIds", JsonArray()).list.map { it as String }
            if (entityIds.isEmpty()) {
                completableFuture.completeExceptionally(IllegalArgumentException("entityIds must not be empty"))
                return@launch
            } else if (metricIds.isEmpty()) {
                completableFuture.completeExceptionally(IllegalArgumentException("metricIds must not be empty"))
                return@launch
            }

            val step = MetricStep.valueOf(vars.getString("step"))
            val start = Instant.from(step.formatter.parse(vars.getString("start")))
            val stop = vars.getString("stop")?.let { Instant.from(step.formatter.parse(it)) }

            EventBusService.getProxy(
                discovery, LiveViewService::class.java,
                JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
            ) {
                if (it.succeeded()) {
                    it.result().getHistoricalMetrics(entityIds, metricIds, step, start, stop).onComplete {
                        if (it.succeeded()) {
                            completableFuture.complete(it.result())
                        } else {
                            completableFuture.completeExceptionally(it.cause())
                        }
                    }
                } else {
                    completableFuture.completeExceptionally(it.cause())
                }
            }
        }
        return completableFuture
    }

    private fun getClientAccessors(env: DataFetchingEnvironment): CompletableFuture<List<ClientAccess>> {
        val completableFuture = CompletableFuture<List<ClientAccess>>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, GET_CLIENT_ACCESSORS, completableFuture)) {
                    return@launch
                }
            }

            completableFuture.complete(SourceStorage.getClientAccessors())
        }
        return completableFuture
    }

    private fun addClientAccess(env: DataFetchingEnvironment): CompletableFuture<ClientAccess> {
        val completableFuture = CompletableFuture<ClientAccess>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, ADD_CLIENT_ACCESS, completableFuture)) {
                    return@launch
                }
            }

            completableFuture.complete(SourceStorage.addClientAccess())
        }
        return completableFuture
    }

    private fun removeClientAccess(env: DataFetchingEnvironment): CompletableFuture<Boolean> {
        val completableFuture = CompletableFuture<Boolean>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, REMOVE_CLIENT_ACCESS, completableFuture)) {
                    return@launch
                }
            }

            completableFuture.complete(SourceStorage.removeClientAccess(env.getArgument("id")))
        }
        return completableFuture
    }

    private fun refreshClientAccess(env: DataFetchingEnvironment): CompletableFuture<ClientAccess> {
        val completableFuture = CompletableFuture<ClientAccess>()
        launch(vertx.dispatcher()) {
            if (jwtEnabled) {
                val devId = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java)
                    .user().principal().getString("developer_id")
                if (SourceStorage.requiresPermission(devId, UPDATE_CLIENT_ACCESS, completableFuture)) {
                    return@launch
                }
            }

            try {
                completableFuture.complete(SourceStorage.refreshClientAccess(env.getArgument("id")))
            } catch (e: Exception) {
                completableFuture.completeExceptionally(e)
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
        if (liveInstrument is LiveBreakpoint && liveInstrument.variableControl != null) {
            liveInstrument.variableControl?.variableTypeConfig.let {
                val variableTypeMap = JsonObject.mapFrom(it).map
                val variableTypeArray = variableTypeMap
                    .map { mapOf("type" to it.key, "control" to it.value) }.toTypedArray()
                (rtnMap["variableControl"] as MutableMap<String, Any>).put("variableTypeConfig", variableTypeArray)
            }
            liveInstrument.variableControl?.variableNameConfig.let {
                val variableNameMap = JsonObject.mapFrom(it).map
                val variableNameArray = variableNameMap
                    .map { mapOf("name" to it.key, "control" to it.value) }.toTypedArray()
                (rtnMap["variableControl"] as MutableMap<String, Any>).put("variableNameConfig", variableNameArray)
            }
        }
        return rtnMap
    }
}
