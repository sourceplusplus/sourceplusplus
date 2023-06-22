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

import graphql.TypeResolutionEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.TypeResolver
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.LiveMeter

class LiveInstrumentTypeResolver : TypeResolver {

    override fun getType(it: TypeResolutionEnvironment): GraphQLObjectType {
        return if ((it.getObject() as Any) is LiveBreakpoint ||
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
    }
}
