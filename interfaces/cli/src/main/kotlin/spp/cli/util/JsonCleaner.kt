package spp.cli.util

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

@Suppress("UNCHECKED_CAST")
object JsonCleaner {
    fun cleanJson(json: JsonObject): JsonObject {
        val cleanJson = JsonObject()
        json.fieldNames().forEach {
            if (it != "__typename") {
                when (val value = json.getValue(it)) {
                    is JsonObject -> cleanJson.put(it, cleanJson(value))
                    is JsonArray -> cleanJson.put(it, cleanJsonArray(value))
                    else -> cleanJson.put(it, value)
                }
            }
        }

        //reformat meta
        if (cleanJson.containsKey("meta") && cleanJson.getValue("meta") is JsonArray) {
            val metaArr = cleanJson.getJsonArray("meta")
            val metaOb = JsonObject()
            for (i in 0 until metaArr.size()) {
                val entry = metaArr.getJsonObject(i)
                metaOb.put(entry.getString("name"), entry.getValue("value"))
            }
            cleanJson.put("meta", metaOb)
        }

        return cleanJson
    }

    private fun cleanJsonArray(jsonArray: JsonArray): JsonArray {
        val cleanJsonArray = JsonArray()
        for (i in 0 until jsonArray.size()) {
            when (val value = jsonArray.getValue(i)) {
                is JsonObject -> cleanJsonArray.add(cleanJson(value))
                is JsonArray -> cleanJsonArray.add(cleanJsonArray(value))
                is String -> cleanJsonArray.add(value)
                else -> throw UnsupportedOperationException("Type: " + value.javaClass.simpleName)
            }
        }
        return cleanJsonArray
    }
}
