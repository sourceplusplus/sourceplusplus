package spp.platform.util

import com.sourceplusplus.protocol.service.live.LiveInstrumentServiceVertxProxyHandler
import com.sourceplusplus.protocol.service.live.LiveViewServiceVertxProxyHandler
import com.sourceplusplus.protocol.service.logging.LogCountIndicatorServiceVertxProxyHandler
import com.sourceplusplus.protocol.service.tracing.LocalTracingServiceVertxProxyHandler
import kotlin.concurrent.getOrSet

object RequestContext {

    private val threadCtx: ThreadLocal<Map<String, String>> = ThreadLocal<Map<String, String>>()

    fun put(value: Map<String, String>) {
        threadCtx.getOrSet { mutableMapOf() }
        (threadCtx.get() as MutableMap).putAll(value)
    }

    fun put(key: String, value: String) {
        threadCtx.getOrSet { mutableMapOf() }
        (threadCtx.get() as MutableMap)[key] = value
    }

    fun get(): Map<String, String> {
        val globalCtx = mutableMapOf<String, String>()
        //local
        threadCtx.get()?.entries?.forEach { globalCtx[it.key] = it.value }
        threadCtx.remove()

        //services
        LiveInstrumentServiceVertxProxyHandler._headers.get()?.entries()?.forEach { globalCtx[it.key] = it.value }
        LiveInstrumentServiceVertxProxyHandler._headers.remove()
        LiveViewServiceVertxProxyHandler._headers.get()?.entries()?.forEach { globalCtx[it.key] = it.value }
        LiveViewServiceVertxProxyHandler._headers.remove()
        LocalTracingServiceVertxProxyHandler._headers.get()?.entries()?.forEach { globalCtx[it.key] = it.value }
        LocalTracingServiceVertxProxyHandler._headers.remove()
        LogCountIndicatorServiceVertxProxyHandler._headers.get()?.entries()?.forEach { globalCtx[it.key] = it.value }
        LogCountIndicatorServiceVertxProxyHandler._headers.remove()
        return globalCtx
    }
}
