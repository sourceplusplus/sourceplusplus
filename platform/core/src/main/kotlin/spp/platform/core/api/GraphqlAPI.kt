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
package spp.platform.core.api

import graphql.ExceptionWhileDataFetching
import graphql.GraphQL
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.schema.Coercing
import graphql.schema.CoercingParseValueException
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLScalarType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeRuntimeWiring
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler
import io.vertx.ext.web.handler.graphql.instrumentation.VertxFutureAdapter
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.servicediscovery.types.EventBusService
import org.slf4j.LoggerFactory
import spp.platform.common.ClusterConnection.discovery
import spp.platform.common.ClusterConnection.router
import spp.platform.common.DeveloperAuth
import spp.protocol.artifact.metrics.MetricStep
import spp.protocol.artifact.trace.TraceSpan
import spp.protocol.artifact.trace.TraceStack
import spp.protocol.instrument.*
import spp.protocol.instrument.LiveInstrumentType.*
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.meter.MeterType
import spp.protocol.instrument.meter.MetricValue
import spp.protocol.instrument.meter.MetricValueType
import spp.protocol.instrument.throttle.InstrumentThrottle
import spp.protocol.instrument.throttle.ThrottleStep
import spp.protocol.instrument.variable.LiveVariableControl
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.platform.general.*
import spp.protocol.service.LiveInstrumentService
import spp.protocol.service.LiveManagementService
import spp.protocol.service.LiveViewService
import spp.protocol.view.HistoricalView
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.rule.RulePartition
import spp.protocol.view.rule.ViewRule
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Serves the GraphQL API, providing access to:
 *
 * [LiveManagementService], [LiveInstrumentService], & [LiveViewService]
 */
@Suppress("TooManyFunctions", "LargeClass") // public API
class GraphqlAPI(private val jwtEnabled: Boolean) : CoroutineVerticle() {

    private val log = LoggerFactory.getLogger(GraphqlAPI::class.java)

    override suspend fun start() {
        val graphql = vertx.executeBlocking {
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
        val schemaFile = vertx.fileSystem().readFileBlocking("spp-api.graphqls").toString()
        val typeDefinitionRegistry = SchemaParser().parse(schemaFile)
        val runtimeWiring = RuntimeWiring.newRuntimeWiring().scalar(
            GraphQLScalarType.newScalar().name("Long")
                .coercing(object : Coercing<Long, Long> {
                    override fun serialize(dataFetcherResult: Any): Long {
                        if (dataFetcherResult is Instant) {
                            return dataFetcherResult.toEpochMilli()
                        }
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
        ).type(TypeRuntimeWiring.newTypeWiring("LiveInstrument").typeResolver {
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
            .type("Query") { withQueryFetchers(it) }
            .type("Mutation") { withMutationFetchers(it) }
            .build()

        val schema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
        return GraphQL.newGraphQL(schema)
            .instrumentation(VertxFutureAdapter.create())
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
            }).build()
    }

    private fun withQueryFetchers(builder: TypeRuntimeWiring.Builder): TypeRuntimeWiring.Builder {
        return builder.dataFetcher("version", this::version)
            .dataFetcher("timeInfo", this::timeInfo)
            .dataFetcher("getAccessToken", this::getAccessToken)
            .dataFetcher("getAccessPermissions", this::getAccessPermissions)
            .dataFetcher("getAccessPermission", this::getAccessPermission)
            .dataFetcher("getRoleAccessPermissions", this::getRoleAccessPermissions)
            .dataFetcher("getDeveloperAccessPermissions", this::getDeveloperAccessPermissions)
            .dataFetcher("getDataRedactions", this::getDataRedactions)
            .dataFetcher("getDataRedaction", this::getDataRedaction)
            .dataFetcher("getRoleDataRedactions", this::getRoleDataRedactions)
            .dataFetcher("getDeveloperDataRedactions", this::getDeveloperDataRedactions)
            .dataFetcher("getRoles", this::getRoles)
            .dataFetcher("getRolePermissions", this::getRolePermissions)
            .dataFetcher("getDeveloperRoles", this::getDeveloperRoles)
            .dataFetcher("getDeveloperPermissions", this::getDeveloperPermissions)
            .dataFetcher("getDevelopers", this::getDevelopers)
            .dataFetcher("getLiveInstrument", this::getLiveInstrument)
            .dataFetcher("getLiveInstruments", this::getLiveInstruments)
            .dataFetcher("getLiveBreakpoints", this::getLiveBreakpoints)
            .dataFetcher("getLiveLogs", this::getLiveLogs)
            .dataFetcher("getLiveMeters", this::getLiveMeters)
            .dataFetcher("getLiveSpans", this::getLiveSpans)
            .dataFetcher("getLiveInstrumentEvents", this::getLiveInstrumentEvents)
            .dataFetcher("getSelf", this::getSelf)
            .dataFetcher("getServices", this::getServices)
            .dataFetcher("getInstances", this::getInstances)
            .dataFetcher("getEndpoints", this::getEndpoints)
            .dataFetcher("searchEndpoints", this::searchEndpoints)
            .dataFetcher("sortMetrics", this::sortMetrics)
            .dataFetcher("getRules", this::getRules)
            .dataFetcher("getRule", this::getRule)
            .dataFetcher("getLiveViews", this::getLiveViews)
            .dataFetcher("getHistoricalMetrics", this::getHistoricalMetrics)
            .dataFetcher("getClientAccessors", this::getClientAccessors)
            .dataFetcher("getTraceStack", this::getTraceStack)
            .dataFetcher("getSystemConfig", this::getSystemConfig)
            .dataFetcher("getSystemConfigValue", this::getSystemConfigValue)
    }

    private fun withMutationFetchers(builder: TypeRuntimeWiring.Builder): TypeRuntimeWiring.Builder {
        return builder.dataFetcher("reset", this::reset)
            .dataFetcher("addDataRedaction", this::addDataRedaction)
            .dataFetcher("updateDataRedaction", this::updateDataRedaction)
            .dataFetcher("removeDataRedaction", this::removeDataRedaction)
            .dataFetcher("addRoleDataRedaction", this::addRoleDataRedaction)
            .dataFetcher("removeRoleDataRedaction", this::removeRoleDataRedaction)
            .dataFetcher("addAccessPermission", this::addAccessPermission)
            .dataFetcher("removeAccessPermission", this::removeAccessPermission)
            .dataFetcher("addRoleAccessPermission", this::addRoleAccessPermission)
            .dataFetcher("removeRoleAccessPermission", this::removeRoleAccessPermission)
            .dataFetcher("addRole", this::addRole)
            .dataFetcher("removeRole", this::removeRole)
            .dataFetcher("addRolePermission", this::addRolePermission)
            .dataFetcher("removeRolePermission", this::removeRolePermission)
            .dataFetcher("addDeveloperRole", this::addDeveloperRole)
            .dataFetcher("removeDeveloperRole", this::removeDeveloperRole)
            .dataFetcher("addDeveloper", this::addDeveloper)
            .dataFetcher("removeDeveloper", this::removeDeveloper)
            .dataFetcher("refreshAuthorizationCode", this::refreshAuthorizationCode)
            .dataFetcher("removeLiveInstrument", this::removeLiveInstrument)
            .dataFetcher("removeLiveInstruments", this::removeLiveInstruments)
            .dataFetcher("clearLiveInstruments", this::clearLiveInstruments)
            .dataFetcher("addLiveBreakpoint", this::addLiveBreakpoint)
            .dataFetcher("addLiveLog", this::addLiveLog)
            .dataFetcher("addLiveMeter", this::addLiveMeter)
            .dataFetcher("addLiveSpan", this::addLiveSpan)
            .dataFetcher("saveRule", this::saveRule)
            .dataFetcher("addLiveView", this::addLiveView)
            .dataFetcher("clearLiveViews", this::clearLiveViews)
            .dataFetcher("addClientAccess", this::addClientAccess)
            .dataFetcher("removeClientAccess", this::removeClientAccess)
            .dataFetcher("refreshClientAccess", this::refreshClientAccess)
            .dataFetcher("setSystemConfigValue", this::setSystemConfigValue)
    }

    private fun version(env: DataFetchingEnvironment): Future<String> =
        getLiveManagementService(env).compose { it.getVersion() }

    private fun timeInfo(env: DataFetchingEnvironment): Future<TimeInfo> =
        getLiveManagementService(env).compose { it.getTimeInfo() }

    private fun getAccessToken(env: DataFetchingEnvironment): Future<String> =
        getLiveManagementService(env).compose { it.getAccessToken(env.getArgument("authorizationCode")) }

    private fun getAccessPermissions(env: DataFetchingEnvironment): Future<List<AccessPermission>> =
        getLiveManagementService(env).compose { it.getAccessPermissions() }

    private fun getAccessPermission(env: DataFetchingEnvironment): Future<AccessPermission> =
        getLiveManagementService(env).compose { it.getAccessPermission(env.getArgument("id")) }

    private fun getRoleAccessPermissions(env: DataFetchingEnvironment): Future<List<AccessPermission>> =
        getLiveManagementService(env)
            .compose { it.getRoleAccessPermissions(DeveloperRole.fromString(env.getArgument("role"))) }

    private fun getDeveloperAccessPermissions(env: DataFetchingEnvironment): Future<List<AccessPermission>> =
        getLiveManagementService(env)
            .compose { it.getDeveloperAccessPermissions(env.getArgument("developerId")) }

    private fun getDataRedactions(env: DataFetchingEnvironment): Future<List<DataRedaction>> =
        getLiveManagementService(env).compose { it.getDataRedactions() }

    private fun getDataRedaction(env: DataFetchingEnvironment): Future<DataRedaction> =
        getLiveManagementService(env).compose { it.getDataRedaction(env.getArgument("id")) }

    private fun getRoleDataRedactions(env: DataFetchingEnvironment): Future<List<DataRedaction>> =
        getLiveManagementService(env)
            .compose { it.getRoleDataRedactions(DeveloperRole.fromString(env.getArgument("role"))) }

    private fun getDeveloperDataRedactions(env: DataFetchingEnvironment): Future<List<DataRedaction>> =
        getLiveManagementService(env)
            .compose { it.getDeveloperDataRedactions(env.getArgument("developerId")) }

    private fun getRoles(env: DataFetchingEnvironment): Future<List<DeveloperRole>> =
        getLiveManagementService(env).compose { it.getRoles() }

    private fun getRolePermissions(env: DataFetchingEnvironment): Future<List<RolePermission>> =
        getLiveManagementService(env)
            .compose { it.getRolePermissions(DeveloperRole.fromString(env.getArgument("role"))) }

    private fun getDeveloperRoles(env: DataFetchingEnvironment): Future<List<DeveloperRole>> =
        getLiveManagementService(env).compose {
            it.getDeveloperRoles(
                env.getArgument<String>("id").lowercase().replace(" ", "")
            )
        }

    private fun getDeveloperPermissions(env: DataFetchingEnvironment): Future<List<RolePermission>> =
        getLiveManagementService(env).compose {
            it.getDeveloperPermissions(
                env.getArgument<String>("id").lowercase().replace(" ", "")
            )
        }

    private fun getDevelopers(env: DataFetchingEnvironment): Future<List<Developer>> =
        getLiveManagementService(env).compose { it.getDevelopers() }

    private fun getSelf(env: DataFetchingEnvironment): Future<SelfInfo> =
        getLiveManagementService(env).compose { it.getSelf() }

    private fun getServices(env: DataFetchingEnvironment): Future<List<Service>> =
        getLiveManagementService(env).compose { it.getServices() }

    private fun getInstances(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> =
        getLiveManagementService(env).compose { it.getInstances(env.getArgument("serviceId")) }
            .map { instances -> instances.map { fixJsonMaps(it) } }

    private fun getEndpoints(env: DataFetchingEnvironment): Future<List<ServiceEndpoint>> =
        getLiveManagementService(env).compose {
            it.getEndpoints(
                env.getArgument("serviceId"),
                env.getArgument<Int?>("limit").toString().toIntOrNull()
            )
        }

    private fun searchEndpoints(env: DataFetchingEnvironment): Future<List<ServiceEndpoint>> =
        getLiveManagementService(env).compose {
            it.searchEndpoints(
                env.getArgument("serviceId"),
                env.getArgument("keyword"),
                env.getArgument<Int?>("limit").toString().toIntOrNull()
            )
        }

    private fun sortMetrics(env: DataFetchingEnvironment): Future<List<SelectedRecord>> {
        return getLiveManagementService(env).compose {
            it.sortMetrics(
                env.getArgument("name"),
                env.getArgument<String?>("parentService"),
                env.getArgument("normal"),
                env.getArgument<String?>("scope")?.let { Scope.valueOf(it) },
                env.getArgument("topN"),
                env.getArgument<String>("order").let { Order.valueOf(it) },
                env.getArgument<String>("step").let { MetricStep.valueOf(it) },
                Instant.ofEpochMilli(env.getArgument("start")),
                env.getArgument<Long?>("stop")?.let { Instant.ofEpochMilli(it) } ?: Instant.now()
            )
        }
    }

    private fun refreshAuthorizationCode(env: DataFetchingEnvironment): Future<Developer> =
        getLiveManagementService(env).compose { it.refreshAuthorizationCode(env.getArgument("id")) }

    private fun getLiveInstrument(env: DataFetchingEnvironment): Future<Map<String, Any>?> =
        getLiveInstrumentService(env).compose {
            it.getLiveInstrument(env.getArgument("id"), env.getArgument("includeArchive") ?: false)
        }.map { it?.let { fixJsonMaps(it) } }

    private fun getLiveInstruments(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> =
        getLiveInstrumentService(env).compose { it.getLiveInstruments() }.map { instruments ->
            instruments.map { fixJsonMaps(it) }
        }

    private fun getLiveBreakpoints(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> =
        getLiveInstrumentService(env).compose { it.getLiveInstruments(BREAKPOINT) }.map { instruments ->
            instruments.map { fixJsonMaps(it) }
        }

    private fun getLiveLogs(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> =
        getLiveInstrumentService(env).compose { it.getLiveInstruments(LOG) }.map { instruments ->
            instruments.map { fixJsonMaps(it) }
        }

    private fun getLiveMeters(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> =
        getLiveInstrumentService(env).compose { it.getLiveInstruments(METER) }.map { instruments ->
            instruments.map { fixJsonMaps(it) }
        }

    private fun getLiveSpans(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> =
        getLiveInstrumentService(env).compose { it.getLiveInstruments(SPAN) }.map { instruments ->
            instruments.map { fixJsonMaps(it) }
        }

    private fun getLiveInstrumentEvents(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> {
        val instrumentId: String? = env.getArgument("instrumentId")
        val start = env.getArgument("start") ?: 0L
        val stop = env.getArgument("stop") ?: Instant.now().toEpochMilli()
        val offset = env.getArgument("offset") ?: 0
        val limit = env.getArgument("limit") ?: 100

        return getLiveInstrumentService(env).compose {
            it.getLiveInstrumentEvents(
                instrumentId,
                Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(stop),
                offset,
                limit
            )
        }.map { events -> events.map { fixJsonMaps(it) } }
    }

    private fun reset(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose { it.reset() }.map { true }

    private fun addDataRedaction(env: DataFetchingEnvironment): Future<DataRedaction> =
        getLiveManagementService(env).compose {
            it.addDataRedaction(
                env.getArgument<String>("id") ?: UUID.randomUUID().toString(),
                RedactionType.valueOf(env.getArgument("type")),
                env.getArgument("lookup"),
                env.getArgument("replacement")
            )
        }

    private fun updateDataRedaction(env: DataFetchingEnvironment): Future<DataRedaction> =
        getLiveManagementService(env).compose {
            it.updateDataRedaction(
                env.getArgument("id"),
                RedactionType.valueOf(env.getArgument("type")),
                env.getArgument("lookup"),
                env.getArgument("replacement")
            )
        }

    private fun removeDataRedaction(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose { it.removeDataRedaction(env.getArgument("id")) }
            .map { true }

    private fun addRoleDataRedaction(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.addRoleDataRedaction(
                DeveloperRole.fromString(env.getArgument("role")),
                env.getArgument("dataRedactionId")
            )
        }.map { true }

    private fun removeRoleDataRedaction(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.removeRoleDataRedaction(
                DeveloperRole.fromString(env.getArgument("role")),
                env.getArgument("dataRedactionId")
            )
        }.map { true }

    private fun addAccessPermission(env: DataFetchingEnvironment): Future<AccessPermission> =
        getLiveManagementService(env).compose {
            it.addAccessPermission(
                env.getArgument("locationPatterns"),
                AccessType.valueOf(env.getArgument("type"))
            )
        }

    private fun removeAccessPermission(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose { it.removeAccessPermission(env.getArgument("id")) }
            .map { true }

    private fun addRoleAccessPermission(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.addRoleAccessPermission(
                DeveloperRole.fromString(env.getArgument("role")),
                env.getArgument("accessPermissionId")
            )
        }.map { true }

    private fun removeRoleAccessPermission(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.removeRoleAccessPermission(
                DeveloperRole.fromString(env.getArgument("role")),
                env.getArgument("accessPermissionId")
            )
        }.map { true }

    private fun addRole(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose { it.addRole(DeveloperRole.fromString(env.getArgument("role"))) }

    private fun removeRole(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose { it.removeRole(DeveloperRole.fromString(env.getArgument("role"))) }

    private fun addRolePermission(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.addRolePermission(
                DeveloperRole.fromString(env.getArgument("role")),
                RolePermission.valueOf(env.getArgument("permission"))
            )
        }.map { true }

    private fun removeRolePermission(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.removeRolePermission(
                DeveloperRole.fromString(env.getArgument("role")),
                RolePermission.valueOf(env.getArgument("permission"))
            )
        }.map { true }

    private fun addDeveloperRole(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.addDeveloperRole(
                env.getArgument<String>("id").lowercase().replace(" ", ""),
                DeveloperRole.fromString(env.getArgument("role"))
            )
        }.map { true }

    private fun removeDeveloperRole(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.removeDeveloperRole(
                env.getArgument<String>("id").lowercase().replace(" ", ""),
                DeveloperRole.fromString(env.getArgument("role"))
            )
        }.map { true }

    private fun addDeveloper(env: DataFetchingEnvironment): Future<Developer> =
        getLiveManagementService(env).compose {
            it.addDeveloper(
                env.getArgument<String>("id").lowercase().replace(" ", ""),
                env.getArgument("authorizationCode")
            )
        }

    private fun removeDeveloper(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.removeDeveloper(
                env.getArgument<String>("id").lowercase().replace(" ", "")
            )
        }.map { true }

    private fun removeLiveInstrument(env: DataFetchingEnvironment): Future<Map<String, Any>?> =
        getLiveInstrumentService(env).compose { it.removeLiveInstrument(env.getArgument("id")) }
            .map { it?.let { fixJsonMaps(it) } }

    private fun removeLiveInstruments(env: DataFetchingEnvironment): Future<List<Map<String, Any>>> {
        val source: String = env.getArgument("source")
        val line: Int = env.getArgument("line")

        val location = LiveSourceLocation(source, line)
        return getLiveInstrumentService(env).compose { it.removeLiveInstruments(location) }.map { instrument ->
            instrument.map { fixJsonMaps(it) }
        }
    }

    private fun clearLiveInstruments(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveInstrumentService(env).compose { it.clearLiveInstruments(null) }

    private fun addLiveBreakpoint(env: DataFetchingEnvironment): Future<Map<String, Any>> {
        val input = JsonObject.mapFrom(env.getArgument("input"))
        val id: String? = input.getString("id")
        val variableControl = input.getJsonObject("variableControl")
        val location = input.getJsonObject("location")
        val locationSource = location.getString("source")
        val locationLine = location.getInteger("line")

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

        val instrument = LiveBreakpoint(
            id = id,
            variableControl = variableControl?.let { LiveVariableControl(it) },
            location = LiveSourceLocation(locationSource, locationLine),
            condition = condition,
            expiresAt = expiresAt,
            hitLimit = hitLimit ?: 1,
            applyImmediately = applyImmediately ?: false,
            throttle = throttle,
            meta = toJsonMap(input.getJsonArray("meta"))
        )
        return getLiveInstrumentService(env).compose { it.addLiveInstrument(instrument) }.map { fixJsonMaps(it) }

    }

    private fun addLiveLog(env: DataFetchingEnvironment): Future<Map<String, Any>> {
        val input = JsonObject.mapFrom(env.getArgument("input"))
        val id: String? = input.getString("id")
        val location = input.getJsonObject("location")
        val locationSource = location.getString("source")
        val locationLine = location.getInteger("line")

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

        val instrument = LiveLog(
            id = id,
            logFormat = input.getString("logFormat"), logArguments = logArguments,
            location = LiveSourceLocation(locationSource, locationLine),
            condition = condition,
            expiresAt = expiresAt,
            hitLimit = hitLimit ?: 1,
            applyImmediately = applyImmediately ?: false,
            throttle = throttle,
            meta = toJsonMap(input.getJsonArray("meta"))
        )
        return getLiveInstrumentService(env).compose { it.addLiveInstrument(instrument) }.map { fixJsonMaps(it) }

    }

    private fun addLiveMeter(env: DataFetchingEnvironment): Future<Map<String, Any>> {
        val input = JsonObject.mapFrom(env.getArgument("input"))
        val id: String? = input.getString("id")
        val location = input.getJsonObject("location")
        val locationSource = location.getString("source")
        val locationLine = location.getInteger("line")

        val metricValueInput = input.getJsonObject("metricValue")
        val metricValue = MetricValue(
            MetricValueType.valueOf(metricValueInput.getString("valueType")),
            metricValueInput.getString("value")
        )

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

        val instrument = LiveMeter(
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
        return getLiveInstrumentService(env).compose { it.addLiveInstrument(instrument) }.map { fixJsonMaps(it) }

    }

    private fun addLiveSpan(env: DataFetchingEnvironment): Future<Map<String, Any>> {
        val input = JsonObject.mapFrom(env.getArgument("input"))
        val id: String? = input.getString("id")
        val operationName = input.getString("operationName")
        val location = input.getJsonObject("location")
        val locationSource = location.getString("source")
        val locationLine = -1 //location.getInteger("line")

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

        val instrument = LiveSpan(
            id = id,
            operationName = operationName,
            location = LiveSourceLocation(locationSource, locationLine),
            condition = condition,
            expiresAt = expiresAt,
            hitLimit = hitLimit ?: -1,
            applyImmediately = applyImmediately ?: false,
            throttle = throttle,
            meta = toJsonMap(input.getJsonArray("meta"))
        )
        return getLiveInstrumentService(env).compose { it.addLiveInstrument(instrument) }.map { fixJsonMaps(it) }

    }

    private fun saveRule(env: DataFetchingEnvironment): Future<ViewRule> {
        val input = JsonObject.mapFrom(env.getArgument("input"))
        val viewRule = ViewRule(
            name = input.getString("name"),
            exp = input.getString("exp"),
            partitions = (input.map["partitions"] as List<*>).map { RulePartition(JsonObject.mapFrom(it)) },
            meterIds = (input.map["meterIds"] as List<*>).map { it.toString() },
        )
        return getLiveViewService(env).compose { it.saveRule(viewRule) }
    }

    private fun addLiveView(env: DataFetchingEnvironment): Future<LiveView> {
        val input = JsonObject.mapFrom(env.getArgument("input"))
        val viewConfig = LiveViewConfig(
            input.getJsonObject("viewConfig").getString("viewName"),
            input.getJsonObject("viewConfig").getJsonArray("viewMetrics").map { it.toString() },
            input.getJsonObject("viewConfig").getInteger("refreshRateLimit") ?: -1,
        )
        val subscription = LiveView(
            entityIds = input.getJsonArray("entityIds").list.map { it as String }.toMutableSet(),
            viewConfig = viewConfig
        )

        return getLiveViewService(env).compose { it.addLiveView(subscription) }
    }

    private fun getRules(env: DataFetchingEnvironment): Future<List<ViewRule>> =
        getLiveViewService(env).compose { it.getRules() }

    private fun getRule(env: DataFetchingEnvironment): Future<ViewRule?> =
        getLiveViewService(env).compose { it.getRule(env.getArgument("ruleName")) }


    private fun getLiveViews(env: DataFetchingEnvironment): Future<List<LiveView>> =
        getLiveViewService(env).compose { it.getLiveViews() }

    private fun clearLiveViews(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveViewService(env).compose { it.clearLiveViews() }.map { true }

    private fun getHistoricalMetrics(env: DataFetchingEnvironment): Future<HistoricalView> {
        val vars = JsonObject.mapFrom(env.variables)
        val entityIds = vars.getJsonArray("entityIds", JsonArray()).list.map { it as String }
        val metricIds = vars.getJsonArray("metricIds", JsonArray()).list.map { it as String }
        if (entityIds.isEmpty()) {
            val future = Promise.promise<HistoricalView>()
            future.fail(IllegalArgumentException("entityIds must be provided"))
            return future.future()
        } else if (metricIds.isEmpty()) {
            val future = Promise.promise<HistoricalView>()
            future.fail(IllegalArgumentException("metricIds must be provided"))
            return future.future()
        }

        val step = MetricStep.valueOf(vars.getString("step"))
        val start = step.toInstant(vars.getString("start"))
        val stop = vars.getString("stop")?.let { step.toInstant(it) }
        val labels = vars.getJsonArray("labels", JsonArray()).list.map { it as String }

        return getLiveViewService(env).compose {
            it.getHistoricalMetrics(entityIds, metricIds, step, start, stop, labels)
        }
    }

    private fun getClientAccessors(env: DataFetchingEnvironment): Future<List<ClientAccess>> =
        getLiveManagementService(env).compose { it.getClientAccessors() }

    private fun addClientAccess(env: DataFetchingEnvironment): Future<ClientAccess> =
        getLiveManagementService(env).compose { it.addClientAccess() }

    private fun removeClientAccess(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose { it.removeClientAccess(env.getArgument("id")) }
            .map { true }

    private fun refreshClientAccess(env: DataFetchingEnvironment): Future<ClientAccess> =
        getLiveManagementService(env).compose { it.refreshClientAccess(env.getArgument("id")) }

    private fun getTraceStack(env: DataFetchingEnvironment): Future<Map<String, Any>?> =
        getLiveViewService(env).compose { it.getTraceStack(env.getArgument("traceId")) }
            .map { fixJsonMaps(it) }

    private fun getSystemConfig(env: DataFetchingEnvironment): Future<List<Map<String, String>>> =
        getLiveManagementService(env).compose { it.getConfiguration() }
            .map { fixConfigObject(it) }

    private fun getSystemConfigValue(env: DataFetchingEnvironment): Future<String> =
        getLiveManagementService(env).compose {
            it.getConfigurationValue(env.getArgument("config"))
                .map { value -> value ?: "" }
        }

    private fun setSystemConfigValue(env: DataFetchingEnvironment): Future<Boolean> =
        getLiveManagementService(env).compose {
            it.setConfigurationValue(env.getArgument("config"), env.getArgument("value"))
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

    private fun fixJsonMaps(event: LiveInstrumentEvent): Map<String, Any> {
        val instrument = fixJsonMaps(event.instrument)
        val rtnMap = (JsonObject.mapFrom(event).map as Map<String, Any>).toMutableMap()
        rtnMap["instrument"] = instrument
        rtnMap["occurredAt"] = event.occurredAt.toEpochMilli()
        return rtnMap
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixJsonMaps(instance: ServiceInstance): Map<String, Any> {
        val rtnMap = (JsonObject.mapFrom(instance).map as Map<String, Any>).toMutableMap()
        val attributes = rtnMap["attributes"] as LinkedHashMap<String, String>
        rtnMap["attributes"] = attributes.map { mapOf("key" to it.key, "value" to it.value) }.toTypedArray()
        return rtnMap
    }

    private fun fixConfigObject(json: JsonObject): List<Map<String, String>> {
        return (JsonObject.mapFrom(json).map as Map<String, Any?>).map {
            it.key to (it.value?.toString() ?: "")
        }.map { mapOf("config" to it.first, "value" to it.second) }
    }

    private fun fixJsonMaps(traceStack: TraceStack?): Map<String, Any>? {
        if (traceStack == null) {
            return null
        }

        val rtnMap = mutableMapOf<String, Any>()
        rtnMap["traceSpans"] = traceStack.traceSpans.map { fixJsonMaps(it) }
        return rtnMap
    }

    private fun fixJsonMaps(traceSpan: TraceSpan): Map<String, Any> {
        val rtnMap = (JsonObject.mapFrom(traceSpan).map as Map<String, Any>).toMutableMap()
        rtnMap["startTime"] = traceSpan.startTime.toEpochMilli()
        rtnMap["endTime"] = traceSpan.endTime.toEpochMilli()
        val tags = rtnMap["tags"] as LinkedHashMap<*, *>
        rtnMap["tags"] = tags.map { mapOf("key" to it.key, "value" to it.value) }.toTypedArray()
        val meta = rtnMap["meta"] as LinkedHashMap<*, *>
        rtnMap["meta"] = meta.map { mapOf("key" to it.key, "value" to it.value) }.toTypedArray()
        return rtnMap
    }

    private fun getLiveManagementService(env: DataFetchingEnvironment) =
        getLiveService(env, LiveManagementService::class.java)

    private fun getLiveViewService(env: DataFetchingEnvironment) =
        getLiveService(env, LiveViewService::class.java)

    private fun getLiveInstrumentService(env: DataFetchingEnvironment) =
        getLiveService(env, LiveInstrumentService::class.java)

    private fun <T> getLiveService(env: DataFetchingEnvironment, clazz: Class<T>): Future<T> {
        val promise = Promise.promise<T>()
        var accessToken: String? = null
        if (jwtEnabled) {
            val user = env.graphQlContext.get<RoutingContext>(RoutingContext::class.java).user()
            accessToken = user.principal().getString("access_token")
        }

        EventBusService.getProxy(
            discovery, clazz,
            JsonObject().apply { accessToken?.let { put("headers", JsonObject().put("auth-token", accessToken)) } }
        ) {
            if (it.succeeded()) {
                promise.complete(it.result())
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }
}
