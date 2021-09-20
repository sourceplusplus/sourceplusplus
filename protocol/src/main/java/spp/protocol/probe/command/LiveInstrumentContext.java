package spp.protocol.probe.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.json.Json;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LiveInstrumentContext implements Serializable {

    private Set<String> instruments = new HashSet<>();
    private Set<String> locations = new HashSet<>();

    public LiveInstrumentContext() {
    }

    public List<String> getLiveInstruments() {
        return new ArrayList<>(instruments);
    }

    public LiveInstrumentContext addLiveInstrument(Object liveInstrument) {
        instruments.add(Json.encode(liveInstrument));
        return this;
    }

    public LiveInstrumentContext addLiveInstrument(String liveInstrument) {
        instruments.add(liveInstrument);
        return this;
    }

    public LiveInstrumentContext addLiveInstruments(Collection<String> liveInstruments) {
        this.instruments.addAll(liveInstruments);
        return this;
    }

    public <T> List<T> getLiveInstrumentsCast(Class<T> clazz) {
        return instruments.stream().map(it -> Json.decodeValue(it, clazz)).collect(Collectors.toList());
    }

    public List<String> getLocations() {
        return new ArrayList<>(locations);
    }

    public void addLocation(Object location) {
        locations.add(Json.encode(location));
    }

    @SuppressWarnings("unused")
    public void addLocations(Collection<String> locations) {
        this.locations = new HashSet<>(locations);
    }

    @SuppressWarnings("unused")
    public <T> List<T> getLocationsCast(Class<T> clazz) {
        return locations.stream().map(it -> Json.decodeValue(it, clazz)).collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    public void setLiveInstruments(Set<String> instruments) {
        this.instruments = instruments;
    }

    @SuppressWarnings("unused")
    public void setLocations(Set<String> locations) {
        this.locations = locations;
    }

    @Override
    public String toString() {
        return "LiveInstrumentContext{" +
                "instruments=" + instruments +
                ", locations=" + locations +
                '}';
    }
}
