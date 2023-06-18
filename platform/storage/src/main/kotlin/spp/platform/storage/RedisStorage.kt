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
package spp.platform.storage

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.Counter
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.coroutines.await
import io.vertx.redis.client.Command.*
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisAPI
import io.vertx.redis.client.Request.cmd
import mu.KotlinLogging
import spp.protocol.instrument.LiveInstrument
import spp.protocol.instrument.event.LiveInstrumentEvent
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

open class RedisStorage(val vertx: Vertx) : CoreStorage {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Suppress("MemberVisibilityCanBePrivate")
    lateinit var redisClient: Redis
    lateinit var redis: RedisAPI

    override suspend fun init(config: JsonObject) {
        val sdHost = config.getString("host")
        val sdPort = config.getString("port")
        redisClient = Redis.createClient(vertx, "redis://$sdHost:$sdPort")
        redis = RedisAPI.api(redisClient)
    }

    override suspend fun counter(name: String): Counter {
        log.trace { "Getting counter: $name" }
        return vertx.sharedData().getCounter(namespace(name)).await()
    }

    override suspend fun lock(name: String): Lock {
        return vertx.sharedData().getLock(namespace(name)).await()
    }

    override suspend fun lock(name: String, timeout: Long): Lock {
        return if (timeout == -1L) {
            lock(name)
        } else {
            vertx.sharedData().getLockWithTimeout(namespace(name), timeout).await()
        }
    }

    override suspend fun <K, V> map(name: String): AsyncMap<K, V> {
        return vertx.sharedData().getAsyncMap<K, V>(namespace("maps:$name")).await()
    }

    override suspend fun <T> get(name: String): T? {
        return map<String, T>("global.properties").get(name).await()
    }

    override suspend fun <T> put(name: String, value: T) {
        map<String, T>("global.properties").put(name, value).await()
    }

    override suspend fun getDevelopers(): List<Developer> {
        val devIds = redis.smembers(namespace("developers:ids")).await()
        return devIds.map { Developer(it.toString(UTF_8)) }
    }

    override suspend fun getDeveloperByAuthorizationCode(code: String): Developer? {
        val devId = redis.get(namespace("developers:authorization_codes:$code")).await()
            ?.toString(UTF_8) ?: return null
        return Developer(devId)
    }

    override suspend fun hasRole(role: DeveloperRole): Boolean {
        return redis.sismember(namespace("roles"), role.roleName).await().toBoolean()
    }

    override suspend fun removeRole(role: DeveloperRole): Boolean {
        getRolePermissions(role).forEach {
            removePermissionFromRole(role, it)
        }
        return redis.srem(listOf(namespace("roles"), role.roleName)).await().toBoolean()
    }

    override suspend fun addRole(role: DeveloperRole): Boolean {
        return redis.sadd(listOf(namespace("roles"), role.roleName)).await().toBoolean()
    }

    override suspend fun hasDeveloper(id: String): Boolean {
        return redis.sismember(namespace("developers:ids"), id).await().toBoolean()
    }

    override suspend fun addDeveloper(id: String, authorizationCode: String): Developer {
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SADD).arg(namespace("developers:ids")).arg(id),
                cmd(SET).arg(namespace("developers:authorization_codes:$authorizationCode")).arg(id),
                cmd(SADD).arg(namespace("developers:authorization_codes")).arg(authorizationCode),
                cmd(SET).arg(namespace("developers:ids:$id:authorization_code")).arg(authorizationCode),
                cmd(EXEC)
            )
        ).await()
        return Developer(id, authorizationCode)
    }

    override suspend fun removeDeveloper(id: String) {
        val authorizationCode = getAuthorizationCode(id)
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SREM).arg(namespace("developers:ids")).arg(id),
                cmd(DEL).arg(namespace("developers:authorization_codes:$authorizationCode")),
                cmd(SREM).arg(namespace("developers:authorization_codes")).arg(authorizationCode),
                cmd(DEL).arg(namespace("developers:ids:$id:authorization_code")),
                cmd(DEL).arg(namespace("developers:$id:roles")),
                cmd(EXEC)
            )
        ).await()
    }

    override suspend fun getAuthorizationCode(id: String): String {
        return redis.get(namespace("developers:ids:$id:authorization_code")).await().toString(UTF_8)
    }

    override suspend fun setAuthorizationCode(id: String, code: String) {
        //remove existing token
        val existingToken = redis.get(namespace("developers:ids:$id:authorization_code")).await()
        if (existingToken != null) {
            val existingTokenStr = existingToken.toString(UTF_8)
            if (existingTokenStr.equals(code)) {
                return //no change in access token; ignore
            } else {
                redis.srem(listOf(namespace("developers:authorization_codes"), existingTokenStr)).await()
                redis.del(listOf(namespace("developers:authorization_codes:$existingToken"))).await()
            }
        } else {
            //add developer first
            redis.sadd(listOf(namespace("developers:ids"), id)).await()
        }

        //set new token
        redis.set(listOf(namespace("developers:authorization_codes:$code"), id)).await()
        redis.sadd(listOf(namespace("developers:authorization_codes"), code)).await()
        redis.set(listOf(namespace("developers:ids:$id:authorization_code"), code)).await()
    }

    override suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        val roles = redis.smembers(namespace("developers:$developerId:roles")).await()
        return roles.map { DeveloperRole.fromString(it.toString(UTF_8)) }
    }

    override suspend fun getRoleAccessPermissions(role: DeveloperRole): Set<AccessPermission> {
        val accessPermissions = redis.smembers(namespace("roles:${role.roleName}:access_permissions")).await()
        return accessPermissions.map { getAccessPermission(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun getAccessPermissions(): Set<AccessPermission> {
        val accessPermissions = redis.smembers(namespace("access_permissions")).await()
        return accessPermissions.map { getAccessPermission(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun hasAccessPermission(id: String): Boolean {
        return redis.sismember(namespace("access_permissions"), id).await().toBoolean()
    }

    override suspend fun getAccessPermission(id: String): AccessPermission {
        val accessPermissions = redis.get(namespace("access_permissions:$id")).await()
        val dataObject = JsonObject(accessPermissions.toString(UTF_8))
        return AccessPermission(
            id,
            dataObject.getJsonArray("locationPatterns").map { it.toString() },
            AccessType.valueOf(dataObject.getString("type"))
        )
    }

    override suspend fun addAccessPermission(id: String, locationPatterns: List<String>, type: AccessType) {
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SADD).arg(namespace("access_permissions")).arg(id),
                cmd(SET).arg(namespace("access_permissions:$id")).arg(
                    JsonObject()
                        .put("locationPatterns", locationPatterns)
                        .put("type", type.name)
                        .toString()
                ),
                cmd(EXEC)
            )
        ).await()
    }

    override suspend fun removeAccessPermission(id: String) {
        getRoles().forEach {
            removeAccessPermissionFromRole(id, it)
        }
        redis.srem(listOf(namespace("access_permissions"), id)).await()
        redis.del(listOf(namespace("access_permissions:$id"))).await()
    }

    override suspend fun addAccessPermissionToRole(id: String, role: DeveloperRole) {
        redis.sadd(listOf(namespace("roles:${role.roleName}:access_permissions"), id)).await()
    }

    override suspend fun removeAccessPermissionFromRole(id: String, role: DeveloperRole) {
        redis.srem(listOf(namespace("roles:${role.roleName}:access_permissions"), id)).await()
    }

    override suspend fun getDataRedactions(): Set<DataRedaction> {
        val roles = redis.smembers(namespace("data_redactions")).await()
        return roles.mapNotNull { getDataRedaction(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun getDataRedaction(id: String): DataRedaction? {
        val dataRedactionJson = redis.get(namespace("data_redactions:$id")).await()?.toString(UTF_8)
        return dataRedactionJson?.let { DataRedaction(JsonObject(it)) }
    }

    override suspend fun addDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SET, namespace("data_redactions:$id"), Json.encode(DataRedaction(id, type, lookup, replacement))),
                cmd(SADD, namespace("data_redactions"), id),
                cmd(EXEC)
            )
        ).await()
    }

    override suspend fun updateDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        redis.set(
            listOf(namespace("data_redactions:$id"), Json.encode(DataRedaction(id, type, lookup, replacement)))
        ).await()
    }

    override suspend fun removeDataRedaction(id: String) {
        getRoles().forEach {
            removeDataRedactionFromRole(id, it)
        }
        redis.srem(listOf(namespace("data_redactions"), id)).await()
        redis.del(listOf(namespace("data_redactions:$id"))).await()
    }

    override suspend fun addDataRedactionToRole(id: String, role: DeveloperRole) {
        redis.sadd(listOf(namespace("roles:${role.roleName}:data_redactions"), id)).await()
    }

    override suspend fun removeDataRedactionFromRole(id: String, role: DeveloperRole) {
        redis.srem(listOf(namespace("roles:${role.roleName}:data_redactions"), id)).await()
    }

    override suspend fun getRoleDataRedactions(role: DeveloperRole): Set<DataRedaction> {
        val dataRedactions = redis.smembers(namespace("roles:${role.roleName}:data_redactions")).await()
        return dataRedactions.mapNotNull { getDataRedaction(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun getRoles(): Set<DeveloperRole> {
        val roles = redis.smembers(namespace("roles")).await()
        return roles.map { DeveloperRole.fromString(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        redis.sadd(listOf(namespace("developers:$id:roles"), role.roleName)).await()
    }

    override suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        redis.srem(listOf(namespace("developers:$id:roles"), role.roleName)).await()
    }

    override suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SADD, namespace("roles"), role.roleName),
                cmd(SADD, namespace("roles:${role.roleName}:permissions"), permission.name),
                cmd(EXEC)
            )
        ).await()
    }

    override suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        redis.srem(listOf(namespace("roles:${role.roleName}:permissions"), permission.name)).await()
    }

    override suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val permissions = redis.smembers(namespace("roles:${role.roleName}:permissions")).await()
        return permissions.map { RolePermission.valueOf(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun addLiveInstrument(instrument: LiveInstrument): LiveInstrument {
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SET, namespace("live_instruments:${instrument.id}"), Json.encode(instrument)),
                cmd(SADD, namespace("live_instruments"), instrument.id),
                cmd(EXEC)
            )
        ).await()
        return instrument
    }

    override suspend fun updateLiveInstrument(id: String, instrument: LiveInstrument): LiveInstrument {
        val instrumentJson = redis.eval(
            listOf(
                "local live_instrument = redis.call('get', KEYS[1])\n" +
                        "if live_instrument then\n" +
                        "    redis.call('set', KEYS[1], ARGV[1])\n" +
                        "    return live_instrument\n" +
                        "else\n" +
                        "    local live_instrument_archive = redis.call('get', KEYS[2])\n" +
                        "    if live_instrument_archive then\n" +
                        "        redis.call('set', KEYS[2], ARGV[1])\n" +
                        "        return live_instrument_archive\n" +
                        "    else\n" +
                        "        return nil\n" +
                        "    end\n" +
                        "end",
                "2",
                namespace("live_instruments:$id"),
                namespace("live_instruments_archive:$id"),
                Json.encode(instrument)
            )
        ).await()?.toString(UTF_8)
        require(instrumentJson != null) { "Live instrument with id $id does not exist" }
        return instrument
    }

    override suspend fun removeLiveInstrument(id: String): Boolean {
        redisClient.batch(
            listOf(
                cmd(MULTI),
                cmd(SREM, namespace("live_instruments"), id),
                cmd(RENAME, namespace("live_instruments:$id"), namespace("live_instruments_archive:$id")),
                cmd(EXEC)
            )
        ).await()
        return redis.get(namespace("live_instruments_archive:$id")).await() != null
    }

    override suspend fun getLiveInstrument(id: String, includeArchive: Boolean): LiveInstrument? {
        val rawInstrument = redis.get(namespace("live_instruments:$id")).await()
        val liveInstrument = if (rawInstrument != null) {
            LiveInstrument.fromJson(JsonObject(rawInstrument.toString(UTF_8)))
        } else {
            null
        }
        if (liveInstrument != null) {
            return liveInstrument
        }

        if (includeArchive) {
            return getArchiveLiveInstrument(id)
        }
        return null
    }

    override suspend fun getLiveInstruments(includeArchive: Boolean): List<LiveInstrument> {
        val rawInstruments = redis.smembers(namespace("live_instruments")).await()
        val liveInstruments = rawInstruments.mapNotNull { getLiveInstrument(it.toString(UTF_8)) }

        if (includeArchive) {
            return liveInstruments + getArchivedLiveInstruments()
        }
        return liveInstruments
    }

    override suspend fun getArchiveLiveInstrument(id: String): LiveInstrument? {
        val rawArchiveInstrument = redis.get(namespace("live_instruments_archive:$id")).await()
        return if (rawArchiveInstrument != null) {
            LiveInstrument.fromJson(JsonObject(rawArchiveInstrument.toString(UTF_8)))
        } else {
            null
        }
    }

    override suspend fun getArchivedLiveInstruments(): List<LiveInstrument> {
        val rawArchiveInstruments = redis.keys(namespace("live_instruments_archive:*")).await()
        val archiveInstruments = rawArchiveInstruments.mapNotNull {
            getLiveInstrument(it.toString(UTF_8).substringAfter("live_instruments_archive:"), true)
        }
        return archiveInstruments
    }

    override suspend fun getLiveInstrumentEvents(
        instrumentId: String?,
        from: Instant?,
        to: Instant?,
        offset: Int,
        limit: Int
    ): List<LiveInstrumentEvent> {
        val events = redis.zrevrangebyscore(
            listOf(
                namespace("live_instrument_events"),
                to?.toEpochMilli()?.toString() ?: "+inf",
                from?.toEpochMilli()?.toString() ?: "-inf",
                "LIMIT",
                offset.toString(),
                limit.toString()
            )
        ).await()
        return events.map { LiveInstrumentEvent.fromJson(JsonObject(it.toString(UTF_8))) }
            .filter { instrumentId == null || it.instrument.id == instrumentId }
    }

    override suspend fun addLiveInstrumentEvent(event: LiveInstrumentEvent): LiveInstrumentEvent {
        redis.zadd(
            listOf(
                namespace("live_instrument_events"),
                event.occurredAt.toEpochMilli().toString(),
                event.toJson().toString()
            )
        ).await()
        return event
    }

    override suspend fun getClientAccessors(): List<ClientAccess> {
        val clientAccessors = redis.smembers(namespace("client_access")).await()
        return clientAccessors.map { ClientAccess(JsonObject(it.toString(UTF_8))) }
    }

    override suspend fun getClientAccess(id: String): ClientAccess? {
        val clientAccessors = getClientAccessors()
        return clientAccessors.find { it.id == id }
    }

    override suspend fun addClientAccess(id: String?, secret: String?): ClientAccess {
        val clientAccess = generateClientAccess(id, secret)
        redis.sadd(listOf(namespace("client_access"), Json.encode(clientAccess))).await()
        return clientAccess
    }

    override suspend fun removeClientAccess(id: String): Boolean {
        val clientAccessors = getClientAccessors()
        if (clientAccessors.any { it.id == id }) {
            val batchCommand = mutableListOf(
                cmd(MULTI),
                cmd(DEL, namespace("client_access"))
            )
            val updatedClientAccessors = clientAccessors.filter { it.id != id }
            if (updatedClientAccessors.isNotEmpty()) {
                batchCommand.add(cmd(SADD).apply {
                    arg(namespace("client_access"))
                    updatedClientAccessors.forEach { arg(it.toJson().toString()) }
                })
            }
            batchCommand.add(cmd(EXEC))
            redisClient.batch(batchCommand).await()
            return true
        }
        return false
    }

    override suspend fun refreshClientAccess(id: String): ClientAccess {
        val clientAccessors = getClientAccessors()
        require(clientAccessors.any { it.id == id }) { "Client accessor with id $id does not exist" }

        var clientAccess: ClientAccess? = null
        redisClient.batch(
            mutableListOf(
                cmd(MULTI),
                cmd(DEL, namespace("client_access")),
                cmd(SADD).apply {
                    arg(namespace("client_access"))
                    clientAccessors.forEach {
                        if (it.id != id) {
                            arg(it.toJson().toString())
                        } else {
                            clientAccess = ClientAccess(id, generateClientSecret())
                            arg(clientAccess!!.toJson().toString())
                        }
                    }
                },
                cmd(EXEC)
            )
        ).await()
        return clientAccess!!
    }
}
