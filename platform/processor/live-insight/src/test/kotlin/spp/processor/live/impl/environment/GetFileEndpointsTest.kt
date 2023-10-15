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
package spp.processor.live.impl.environment

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spp.processor.insight.impl.environment.InsightEnvironment
import java.io.File

class GetFileEndpointsTest {

    @Test
    fun `test java file endpoints`() {
        doTest("java")
    }

    @Test
    fun `test kotlin file endpoints`() {
        doTest("kotlin")
    }

    private fun doTest(lang: String): Unit = runBlocking {
        File("/tmp/idea").mkdirs()
        File("/tmp/idea/idea.properties").createNewFile()
        System.setProperty("idea.home.path", "/tmp/idea")
        val env = InsightEnvironment()
        val className = "${lang.capitalize()}VertxEndpoints"
        env.addSourceDirectory(File("src/test/$lang/application"))

        val vertx = Vertx.vertx()
        val endpoints = env.getAllEndpoints(vertx)
        assertEquals(2, endpoints.size())
        assertTrue(endpoints.any {
            JsonObject.mapFrom(it) == JsonObject()
                .put("uri", "POST:/debug/login-error/login")
                .put(
                    "qualifiedName",
                    "application.$className.login(io.vertx.ext.web.RoutingContext)"
                )
        })
        assertTrue(endpoints.any {
            JsonObject.mapFrom(it) == JsonObject()
                .put("uri", "POST:/debug/login-error/create-user")
                .put(
                    "qualifiedName",
                    "application.$className.createUser(io.vertx.ext.web.RoutingContext)"
                )
        })
    }
}
