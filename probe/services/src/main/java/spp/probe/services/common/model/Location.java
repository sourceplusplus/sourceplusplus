package spp.probe.services.common.model;

import java.io.Serializable;

public final class Location implements Serializable {

    private final String source;
    private final int line;

    public Location(String source, int line) {
        this.source = source;
        this.line = line;
    }

    public String getSource() {
        return source;
    }

    public int getLine() {
        return line;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Location location = (Location) o;
        return line == location.line && java.util.Objects.equals(source, location.source);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(source, line);
    }

    @Override
    public String toString() {
        return "Location{" +
                "source='" + source + '\'' +
                ", line=" + line +
                '}';
    }

    public String toJson() {
        return "{" +
                "\"source\" : \"" + source + "\"" +
                ",\"line\" : " + line
                + "}";
    }
}
