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
package spp.processor.live.impl.instrument.breakpoint

import org.joor.Reflect
import spp.protocol.instrument.variable.LiveVariable
import java.math.BigInteger
import java.time.*
import java.util.*
import kotlin.reflect.jvm.jvmName

object LiveVariablePresentation {

    private val supportedClasses = mapOf<String, () -> Reflect>(
        Date::class.jvmName to { Reflect.on(Date()) },
        Duration::class.jvmName to { Reflect.on(Duration.ofSeconds(1)) },
        Instant::class.jvmName to { Reflect.on(Instant.now()) },
        LocalDate::class.jvmName to { Reflect.on(LocalDate.now()) },
        LocalTime::class.jvmName to { Reflect.on(LocalTime.now()) },
        LocalDateTime::class.jvmName to { Reflect.on(LocalDateTime.now()) },
        OffsetDateTime::class.jvmName to { Reflect.on(OffsetDateTime.now()) },
        OffsetTime::class.jvmName to { Reflect.on(OffsetTime.now()) },
        ZonedDateTime::class.jvmName to { Reflect.on(ZonedDateTime.now()) },
        ZoneOffset::class.jvmName to { Reflect.on(ZoneOffset.ofTotalSeconds(0)) },
        "java.time.ZoneRegion" to { Reflect.onClass("java.time.ZoneRegion").call("ofId", "GMT", false) },
        BigInteger::class.jvmName to { Reflect.on(BigInteger.ZERO) },
        Class::class.jvmName to { Reflect.onClass(Class::class.java) },
    )

    fun format(liveClazz: String?, variable: LiveVariable): String? {
        if (liveClazz !in supportedClasses) return null
        val obj: String? = when (variable.value) {
            is String -> variable.value as String
            is List<*> -> setValues(liveClazz, variable.value as List<LiveVariable>)?.toString()
            else -> variable.value.toString()
        }
        return obj
    }

    private fun setValues(liveClazz: String?, values: List<LiveVariable>): Reflect? {
        val obj = supportedClasses[liveClazz]?.invoke()
        obj?.let {
            values.forEach {
                if (it.liveClazz != null) {
                    val childObj = setValues(it.liveClazz as String, it.value as List<LiveVariable>)

                    obj.set(it.name, childObj)
                } else {
                    obj.set(it.name, asSmallestObject(it.value))
                }
            }
        }
        return obj
    }

    private fun asSmallestObject(value: Any?): Any? {
        if (value is Number) {
            when (value.toLong()) {
                in Byte.MIN_VALUE..Byte.MAX_VALUE -> return value.toByte()
                in Int.MIN_VALUE..Int.MAX_VALUE -> return value.toInt()
                in Long.MIN_VALUE..Long.MAX_VALUE -> return value.toLong()
            }
        }
        return value
    }
}
