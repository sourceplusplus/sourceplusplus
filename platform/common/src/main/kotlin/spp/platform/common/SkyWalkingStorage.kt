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

import org.apache.skywalking.oap.server.core.storage.StorageDAO
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base.EsDAO
import org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao.H2StorageDAO
import org.joor.Reflect

class SkyWalkingStorage(storage: StorageDAO) {

    companion object {
        const val METRIC_PREFIX = "spp"
    }

    private var esDAO: EsDAO? = null
    private var h2Client: JDBCHikariCPClient? = null

    init {
        when (storage) {
            is EsDAO -> {
                esDAO = storage
            }
            is H2StorageDAO -> {
                h2Client = Reflect.on(storage).field("h2Client").get()
            }
            else -> {
                throw IllegalArgumentException("Unsupported storage type: ${storage.javaClass.name}")
            }
        }
        createIndexes()
    }

    private fun createIndexes() {
        if (esDAO != null) {
            val esClient = esDAO!!.client!!
            esClient.createIndex("spp_instrument_hit")
        } else if (h2Client != null) {
            val h2Client = h2Client!!
            h2Client.getConnection(true).use {
                h2Client.execute(
                    it,
                    "CREATE TABLE IF NOT EXISTS spp_instrument_hit (" +
                            "log_pattern_id VARCHAR(255) NOT NULL, " +
                            "log_pattern VARCHAR(255) NOT NULL, PRIMARY KEY (log_pattern_id)" +
                            ")"
                )
            }
        }
    }

    fun addLogPattern(logPatternId: String, logPattern: String) {
        if (esDAO != null) {
            esDAO!!.client!!.forceInsert(
                "spp_instrument_hit", logPatternId,
                mapOf(
                    "log_pattern_id" to logPatternId,
                    "log_pattern" to logPattern
                )
            )
        } else if (h2Client != null) {
            val h2Client = h2Client!!
            h2Client.getConnection(true).use {
                h2Client.executeUpdate(
                    it, "INSERT INTO spp_instrument_hit (log_pattern_id, log_pattern) VALUES (?, ?)",
                    logPatternId, logPattern
                )
            }
        }
    }
}
