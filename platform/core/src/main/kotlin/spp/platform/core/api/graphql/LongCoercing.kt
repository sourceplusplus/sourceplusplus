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
package spp.platform.core.api.graphql

import graphql.schema.Coercing
import graphql.schema.CoercingParseValueException
import java.time.Instant

class LongCoercing : Coercing<Long, Long> {

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

    override fun parseLiteral(input: Any): Long = input as Long
}
