package spp.protocol.probe.command

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.vertx.core.json.Json
import java.io.Serializable
import java.util.stream.Collectors

//todo: treat this as a regular data class
@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveInstrumentContext(
    var instruments: MutableSet<String> = HashSet(),
    var locations: MutableSet<String> = HashSet()
) : Serializable {

    @get:JsonIgnore
    val liveInstruments: List<String>
        get() = instruments.toList()

    fun addLiveInstrument(liveInstrument: Any): LiveInstrumentContext {
        instruments.add(Json.encode(liveInstrument))
        return this
    }

    fun addLiveInstrument(liveInstrument: String): LiveInstrumentContext {
        instruments.add(liveInstrument)
        return this
    }

    fun addLiveInstruments(liveInstruments: Collection<String>): LiveInstrumentContext {
        instruments.addAll(liveInstruments)
        return this
    }

    fun <T> getLiveInstrumentsCast(clazz: Class<T>): List<T> {
        return instruments.stream().map { Json.decodeValue(it, clazz) }.collect(Collectors.toList())
    }

    fun addLocation(location: Any) {
        locations.add(Json.encode(location))
    }

    fun <T> getLocationsCast(clazz: Class<T>): List<T> {
        return locations.stream().map { Json.decodeValue(it, clazz) }.collect(Collectors.toList())
    }
}
