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
package spp.platform.storage

import io.vertx.core.Vertx
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.Counter
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.coroutines.await
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisAPI
import mu.KotlinLogging
import spp.protocol.instrument.LiveInstrument
import spp.protocol.platform.auth.*
import spp.protocol.platform.developer.Developer
import java.nio.charset.StandardCharsets.UTF_8

open class RedisStorage(val vertx: Vertx) : CoreStorage {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    lateinit var redisClient: Redis
    lateinit var redis: RedisAPI

    override suspend fun init(config: JsonObject) {
        val sdHost = config.getString("host")
        val sdPort = config.getString("port")
        redisClient = Redis.createClient(vertx, "redis://$sdHost:$sdPort")
        redis = RedisAPI.api(redisClient.connect().await())
    }

    override suspend fun counter(name: String): Counter {
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

    override suspend fun getDeveloperByAccessToken(token: String): Developer? {
        val devId = redis.get(namespace("developers:access_tokens:$token")).await()
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

    override suspend fun addDeveloper(id: String, token: String): Developer {
        redis.sadd(listOf(namespace("developers:ids"), id)).await()
        redis.set(listOf(namespace("developers:access_tokens:$token"), id)).await()
        redis.sadd(listOf(namespace("developers:access_tokens"), token)).await()
        redis.set(listOf(namespace("developers:ids:$id:access_token"), token)).await()
        return Developer(id, token)
    }

    override suspend fun removeDeveloper(id: String) {
        val accessToken = getAccessToken(id)
        redis.srem(listOf(namespace("developers:ids"), id)).await()
        redis.del(listOf(namespace("developers:access_tokens:$accessToken"))).await()
        redis.srem(listOf(namespace("developers:access_tokens"), accessToken)).await()
        redis.del(listOf(namespace("developers:ids:$id:access_token"))).await()
        redis.del(listOf(namespace("developers:$id:roles"))).await()
    }

    private suspend fun getAccessToken(id: String): String {
        return redis.get(namespace("developers:ids:$id:access_token")).await().toString(UTF_8)
    }

    override suspend fun setAccessToken(id: String, accessToken: String) {
        //remove existing token
        val existingToken = redis.get(namespace("developers:ids:$id:access_token")).await()
        if (existingToken != null) {
            val existingTokenStr = existingToken.toString(UTF_8)
            if (existingTokenStr.equals(accessToken)) {
                return //no change in access token; ignore
            } else {
                redis.srem(listOf(namespace("developers:access_tokens"), existingTokenStr)).await()
                redis.del(listOf(namespace("developers:access_tokens:$existingToken"))).await()
            }
        } else {
            //add developer first
            redis.sadd(listOf(namespace("developers:ids"), id)).await()
        }

        //set new token
        redis.set(listOf(namespace("developers:access_tokens:$accessToken"), id)).await()
        redis.sadd(listOf(namespace("developers:access_tokens"), accessToken)).await()
        redis.set(listOf(namespace("developers:ids:$id:access_token"), accessToken)).await()
    }

    override suspend fun getDeveloperRoles(developerId: String): List<DeveloperRole> {
        val resp = redis.smembers(namespace("developers:$developerId:roles")).await()
        log.trace("getDeveloperRoles: developerId=$developerId, roles=$resp; Type: " + resp::class.qualifiedName)
        return resp.map { DeveloperRole.fromString(it.toString(UTF_8)) }
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
        redis.sadd(listOf(namespace("access_permissions"), id)).await()
        redis.set(
            listOf(
                namespace("access_permissions:$id"),
                JsonObject()
                    .put("locationPatterns", locationPatterns)
                    .put("type", type.name)
                    .toString()
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
        return roles.map { getDataRedaction(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun hasDataRedaction(id: String): Boolean {
        return redis.sismember(namespace("data_redactions"), id).await().toBoolean()
    }

    override suspend fun getDataRedaction(id: String): DataRedaction {
        return DataRedaction(JsonObject(redis.get(namespace("data_redactions:$id")).await().toString(UTF_8)))
    }

    override suspend fun addDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        redis.sadd(listOf(namespace("data_redactions"), id)).await()
        redis.set(listOf(namespace("data_redactions:$id"), Json.encode(DataRedaction(id, type, lookup, replacement))))
            .await()
    }

    override suspend fun updateDataRedaction(id: String, type: RedactionType, lookup: String, replacement: String) {
        redis.set(listOf(namespace("data_redactions:$id"), Json.encode(DataRedaction(id, type, lookup, replacement))))
            .await()
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
        return dataRedactions.map { getDataRedaction(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun getRoles(): Set<DeveloperRole> {
        val roles = redis.smembers(namespace("roles")).await()
        return roles.map { DeveloperRole.fromString(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun addRoleToDeveloper(id: String, role: DeveloperRole) {
        log.trace { "addRoleToDeveloper: id=$id, role=$role" }
        redis.sadd(listOf(namespace("developers:$id:roles"), role.roleName)).await()
    }

    override suspend fun removeRoleFromDeveloper(id: String, role: DeveloperRole) {
        redis.srem(listOf(namespace("developers:$id:roles"), role.roleName)).await()
    }

    override suspend fun addPermissionToRole(role: DeveloperRole, permission: RolePermission) {
        redis.sadd(listOf(namespace("roles"), role.roleName)).await()
        redis.sadd(listOf(namespace("roles:${role.roleName}:permissions"), permission.name)).await()
    }

    override suspend fun removePermissionFromRole(role: DeveloperRole, permission: RolePermission) {
        redis.srem(listOf(namespace("roles:${role.roleName}:permissions"), permission.name)).await()
    }

    override suspend fun getRolePermissions(role: DeveloperRole): Set<RolePermission> {
        val permissions = redis.smembers(namespace("roles:${role.roleName}:permissions")).await()
        return permissions.map { RolePermission.valueOf(it.toString(UTF_8)) }.toSet()
    }

    override suspend fun addLiveInstrument(instrument: LiveInstrument): LiveInstrument {
        redis.sadd(listOf(namespace("live_instruments"), instrument.id)).await()
        redis.set(listOf(namespace("live_instruments:${instrument.id}"), Json.encode(instrument))).await()
        return instrument
    }

    override suspend fun updateLiveInstrument(id: String, instrument: LiveInstrument): LiveInstrument {
        redis.set(listOf(namespace("live_instruments:$id"), Json.encode(instrument))).await()
        return instrument
    }

    override suspend fun removeLiveInstrument(id: String): Boolean {
        redis.srem(listOf(namespace("live_instruments"), id)).await()
        return redis.del(listOf(namespace("live_instruments:$id"))).await().toBoolean()
    }

    override suspend fun getLiveInstrument(id: String): LiveInstrument? {
        val instrument = redis.get(namespace("live_instruments:$id")).await()
        return if (instrument != null) {
            LiveInstrument.fromJson(JsonObject(instrument.toString(UTF_8)))
        } else {
            null
        }
    }

    override suspend fun getLiveInstruments(): List<LiveInstrument> {
        val instruments = redis.smembers(namespace("live_instruments")).await()
        return instruments.mapNotNull { getLiveInstrument(it.toString(UTF_8)) }
    }

    override suspend fun getPendingLiveInstruments(): List<LiveInstrument> {
        return getLiveInstruments().filter { it.pending }
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
            redis.multi().await()
            redis.del(listOf(namespace("client_access"))).await()
            val updatedClientAccessors = clientAccessors.filter { it.id != id }
            if (updatedClientAccessors.isNotEmpty()) {
                redis.sadd(mutableListOf(namespace("client_access")).apply {
                    updatedClientAccessors.forEach {
                        add(Json.encode(it))
                    }
                }).await()
            }
            redis.exec().await()
            return true
        }
        return false
    }

    override suspend fun refreshClientAccess(id: String): ClientAccess {
        val clientAccessors = getClientAccessors()
        if (clientAccessors.find { it.id == id } == null) {
            throw IllegalArgumentException("Client accessor with id $id does not exist")
        }

        var clientAccess: ClientAccess? = null
        redis.multi().await()
        redis.del(listOf(namespace("client_access"))).await()
        redis.sadd(mutableListOf(namespace("client_access")).apply {
            clientAccessors.forEach {
                if (it.id != id) {
                    add(Json.encode(it))
                } else {
                    clientAccess = ClientAccess(id, generateClientSecret())
                    add(Json.encode(clientAccess))
                }
            }
        }).await()
        redis.exec().await()
        return clientAccess!!
    }
}
