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
package spp.processor.live.impl.instrument.breakpoint

import spp.protocol.instrument.variable.LiveVariable
import java.time.*

object LiveVariablePresentation {

    fun format(variable: LiveVariable): String? {
        return when (variable.liveClazz) {
            "java.time.Instant" -> parseInstant(variable).toString()
            "java.time.Duration" -> parseDuration(variable).toString()
            "java.time.LocalDateTime" -> parseLocalDateTime(variable).toString()
            "java.time.OffsetDateTime" -> parseOffsetDateTime(variable).toString()
            "java.time.OffsetTime" -> parseOffsetTime(variable).toString()
            "java.time.ZonedDateTime" -> parseZonedDateTime(variable).toString()
            "java.time.ZoneOffset" -> parseZoneOffset(variable).toString()
            "java.time.LocalDate" -> parseLocalDate(variable).toString()
            "java.time.LocalTime" -> parseLocalTime(variable).toString()
            "java.math.BigInteger" -> parseBigInteger(variable)
            "java.lang.Class" -> parseClass(variable)
            else -> null
        }
    }

    private fun parseInstant(variable: LiveVariable): Instant? {
        val variables = variable.value as List<LiveVariable>
        val seconds = (variables.find { it.name == "seconds" }?.value as Number).toLong()
        val nanos = (variables.find { it.name == "nanos" }?.value as Number).toLong()
        return Instant.ofEpochSecond(seconds, nanos)
    }

    private fun parseDuration(variable: LiveVariable): Duration? {
        val variables = variable.value as List<LiveVariable>
        val seconds = (variables.find { it.name == "seconds" }?.value as Number).toLong()
        val nanos = (variables.find { it.name == "nanos" }?.value as Number).toLong()
        return Duration.ofSeconds(seconds, nanos)
    }

    private fun parseOffsetDateTime(variable: LiveVariable): OffsetDateTime? {
        val variables = variable.value as List<LiveVariable>
        val dateTime = parseLocalDateTime(variables.find { it.name == "dateTime" }!!)
        val offset = parseZoneOffset(variables.find { it.name == "offset" }!!)
        return OffsetDateTime.of(dateTime, offset)
    }

    private fun parseOffsetTime(variable: LiveVariable): OffsetTime? {
        val variables = variable.value as List<LiveVariable>
        val time = parseLocalTime(variables.find { it.name == "time" }!!)
        val offset = parseZoneOffset(variables.find { it.name == "offset" }!!)
        return OffsetTime.of(time, offset)
    }

    private fun parseZonedDateTime(variable: LiveVariable): ZonedDateTime? {
        val variables = variable.value as List<LiveVariable>
        val dateTime = parseLocalDateTime(variables.find { it.name == "dateTime" }!!)
        val offset = parseZoneOffset(variables.find { it.name == "offset" }!!)
        return ZonedDateTime.of(dateTime, offset)
    }

    private fun parseLocalDateTime(variable: LiveVariable): LocalDateTime {
        val variables = variable.value as List<LiveVariable>
        val date = parseLocalDate(variables.find { it.name == "date" }!!)
        val time = parseLocalTime(variables.find { it.name == "time" }!!)
        return LocalDateTime.of(date, time)
    }

    private fun parseZoneOffset(variable: LiveVariable): ZoneOffset {
        val variables = variable.value as List<LiveVariable>
        val totalSeconds = variables.find { it.name == "totalSeconds" }?.value as Int
        return ZoneOffset.ofTotalSeconds(totalSeconds)
    }

    private fun parseLocalDate(variable: LiveVariable): LocalDate {
        val variables = variable.value as List<LiveVariable>
        val year = variables.find { it.name == "year" }?.value as Int
        val month = variables.find { it.name == "month" }?.value as Int
        val day = variables.find { it.name == "day" }?.value as Int
        return LocalDate.of(year, month, day)
    }

    private fun parseLocalTime(variable: LiveVariable): LocalTime {
        val variables = variable.value as List<LiveVariable>
        val hour = variables.find { it.name == "hour" }?.value as Int
        val minute = variables.find { it.name == "minute" }?.value as Int
        val second = variables.find { it.name == "second" }?.value as Int
        val nano = variables.find { it.name == "nano" }?.value as Int
        return LocalTime.of(hour, minute, second, nano)
    }

    private fun parseBigInteger(variable: LiveVariable): String {
        return variable.value.toString() //todo: don't need
    }

    private fun parseClass(variable: LiveVariable): String {
        return variable.value.toString() //todo: don't need
    }
}
