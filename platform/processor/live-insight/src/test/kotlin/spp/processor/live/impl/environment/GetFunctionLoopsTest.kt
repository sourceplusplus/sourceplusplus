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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.jetbrains.artifact.service.ArtifactScopeService
import spp.processor.insight.impl.environment.InsightEnvironment
import java.io.File

class GetFunctionLoopsTest {

    @Test
    fun `test java function loops`() {
        doTest("java")
    }

    @Test
    fun `test kotlin function loops`() {
        doTest("kotlin")
    }

    private fun doTest(lang: String) {
        File("/tmp/idea").mkdirs()
        File("/tmp/idea/idea.properties").createNewFile()
        System.setProperty("idea.home.path", "/tmp/idea")
        val env = InsightEnvironment()
        env.addSourceDirectory(File("src/test/testData/$lang/loops"))

        val fileExtension = if (lang == "kotlin") "kt" else lang
        val functionLoopsFile = env.getProjectFiles().first { it.name == "FunctionLoops.$fileExtension" }
        assertEquals(4, ArtifactScopeService.getLoops(functionLoopsFile).size)
    }
}
