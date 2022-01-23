package spp.platform.core.util

import org.slf4j.helpers.MessageFormatter

object Msg {
    fun msg(pattern: String, vararg args: String): String {
        return MessageFormatter.arrayFormat(pattern, args).message
    }
}
