package spp.platform.core

import java.util.concurrent.ConcurrentHashMap

object SourceSubscriber {
    private val subscriberCache = ConcurrentHashMap<String, String>()

    fun addSubscriber(socketId: String, trackerId: String) {
        subscriberCache[socketId] = trackerId
    }

    //todo: this gets trackerId
    fun getSubscriber(socketId: String): String? {
        return subscriberCache[socketId]
    }
}
