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
package spp.processor.live.impl.instrument

import com.google.common.io.Resources
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.variable.LiveVariable

class LiveInstrumentAnalysisTest {

    @Test
    fun processBpHit1() {
        val bpData = JsonObject(
            Resources.toString(Resources.getResource("bphit1.json"), Charsets.UTF_8)
        )
        val bpHit = LiveBreakpointAnalyzer.transformRawBreakpointHit(bpData)
        assertNotNull(bpHit)

        val topStack = bpHit.stackTrace.first()
        assertEquals(2, topStack.variables.size)

        val thisVar = topStack.variables[0]
        assertEquals("this", thisVar.name)
        assertEquals(3, (thisVar.value as List<*>).size)

        val s2Var = topStack.variables[1]
        assertEquals("s2", s2Var.name)
        assertEquals(4, (s2Var.value as List<*>).size)
        val s2Vars = s2Var.value as List<LiveVariable>
        val course = s2Vars.find { it.name == "course" }!!
        assertEquals(2, (course.value as List<LiveVariable>).size)
        assertEquals(
            2,
            ((course.value as List<LiveVariable>)
                .find { it.name == "time" }!!.value as List<LiveVariable>).size
        )

        val student2 = s2Vars.find { it.name == "student2" }!!
        assertEquals(3, (student2.value as List<LiveVariable>).size)
        assertEquals(
            2,
            ((student2.value as List<LiveVariable>)
                .find { it.name == "course" }!!.value as List<LiveVariable>).size
        )
        assertEquals(
            2,
            (((student2.value as List<LiveVariable>)
                .find { it.name == "course" }!!.value as List<LiveVariable>)
                .find { it.name == "time" }!!.value as List<LiveVariable>).size
        )
    }
}
