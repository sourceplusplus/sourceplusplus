package integration

import com.sourceplusplus.protocol.instrument.breakpoint.LiveBreakpoint
import com.sourceplusplus.protocol.instrument.log.LiveLog
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import spp.cli.Main
import java.io.OutputStream
import java.io.PrintStream
import kotlin.reflect.KClass

abstract class CLIIntegrationTest {
    init {
        Main.standalone = false
        Main.main(
            arrayOf(
                "-v",
                "-c", "../../platform/config/spp-platform.crt",
                "-k", "../../platform/config/spp-platform.key",
                "system", "reset"
            )
        )
    }

    class Interceptor(out: OutputStream) : PrintStream(out, true) {
        val output = StringBuilder()

        override fun print(s: String) {
            output.append(s)
        }

        fun clear() {
            output.clear()
        }
    }

    fun <T : Any> toList(jsonString: String, clazz: KClass<T>): MutableList<T> {
        val value = Json.decodeValue(jsonString) as JsonArray
        val list = mutableListOf<T>()
        for (it in value.withIndex()) {
            val v = value.getJsonObject(it.index)
            if (v.getString("type") == "LOG") {
                list.add(v.mapTo(LiveLog::class.java) as T)
            } else if (v.getString("type") == "BREAKPOINT") {
                list.add(v.mapTo(LiveBreakpoint::class.java) as T)
            } else {
                list.add(v.mapTo(clazz.java) as T)
            }
        }
        return list
    }
}
