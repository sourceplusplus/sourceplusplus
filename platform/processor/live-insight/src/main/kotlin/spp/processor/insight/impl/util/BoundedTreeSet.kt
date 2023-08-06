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
package spp.processor.insight.impl.util

import java.util.*

class BoundedTreeSet<E>(
    private var limit: Int
) : TreeSet<E>() {

    private fun adjust() {
        while (limit < size) {
            remove(last())
        }
    }

    override fun add(element: E): Boolean {
        //replace existing element if it exists
        if (contains(element)) {
            remove(element)
        }

        val out = super.add(element)
        adjust()
        return out
    }

    override fun addAll(elements: Collection<E>): Boolean {
        val out = super.addAll(elements)
        adjust()
        return out
    }
}
