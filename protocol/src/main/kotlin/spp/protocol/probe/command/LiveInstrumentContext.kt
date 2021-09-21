package spp.protocol.probe.command

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.vertx.core.json.Json
import spp.protocol.probe.command.LiveInstrumentCommand.CommandType
import spp.protocol.probe.command.LiveInstrumentContext
import spp.protocol.probe.command.LiveInstrumentCommand
import spp.protocol.platform.error.EventBusUtil
import java.io.Serializable
import java.util.ArrayList
import java.util.HashSet
import java.util.stream.Collectors

@JsonIgnoreProperties(ignoreUnknown = true)
class LiveInstrumentContext : Serializable {
    private var instruments: MutableSet<String> = HashSet()
    private var locations: MutableSet<String> = HashSet()
    val liveInstruments: List<String>
        get() = ArrayList(instruments)

    fun addLiveInstrument(liveInstrument: Any?): LiveInstrumentContext {
        instruments.add(Json.encode(liveInstrument))
        return this
    }

    fun addLiveInstrument(liveInstrument: String): LiveInstrumentContext {
        instruments.add(liveInstrument)
        return this
    }

    fun addLiveInstruments(liveInstruments: Collection<String>?): LiveInstrumentContext {
        instruments.addAll(liveInstruments!!)
        return this
    }

    fun <T> getLiveInstrumentsCast(clazz: Class<T>?): List<T> {
        return instruments.stream().map { it: String? -> Json.decodeValue(it, clazz) }.collect(Collectors.toList())
    }

    fun getLocations(): List<String> {
        return ArrayList(locations)
    }

    fun addLocation(location: Any?) {
        locations.add(Json.encode(location))
    }

    fun addLocations(locations: Collection<String>?) {
        this.locations = HashSet(locations)
    }

    fun <T> getLocationsCast(clazz: Class<T>?): List<T> {
        return locations.stream().map { it: String? -> Json.decodeValue(it, clazz) }.collect(Collectors.toList())
    }

    fun setLiveInstruments(instruments: MutableSet<String>) {
        this.instruments = instruments
    }

    fun setLocations(locations: MutableSet<String>) {
        this.locations = locations
    }

    override fun toString(): String {
        return "LiveInstrumentContext{" +
                "instruments=" + instruments +
                ", locations=" + locations +
                '}'
    }
}