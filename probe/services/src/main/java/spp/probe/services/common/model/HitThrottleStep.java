package spp.probe.services.common.model;

public enum HitThrottleStep {
    SECOND(1000),
    MINUTE(1000 * 60),
    HOUR(1000 * 60 * 60),
    DAY(1000 * 60 * 60 * 24);

    private final int millis;

    HitThrottleStep(int millis) {
        this.millis = millis;
    }

    public long toMillis(long duration) {
        return millis * duration;
    }
}
