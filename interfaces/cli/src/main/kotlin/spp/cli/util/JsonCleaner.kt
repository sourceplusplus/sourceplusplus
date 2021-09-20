package spp.cli.util

import io.vertx.core.json.JsonObject

@Suppress("UNCHECKED_CAST")
object JsonCleaner {
    fun cleanJson(json: JsonObject): JsonObject {
        val cleanJson = JsonObject()
        json.fieldNames().forEach {
            if (it != "__typename") {
                val value = json.getValue(it)
                if (value is JsonObject) {
                    cleanJson.put(it, cleanJson(value))
                } else {
                    cleanJson.put(it, value)
                }
            }
        }
        return cleanJson
    }
}
