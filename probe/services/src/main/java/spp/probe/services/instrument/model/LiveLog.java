package spp.probe.services.instrument.model;

import org.springframework.expression.Expression;
import spp.probe.services.common.model.HitThrottle;
import spp.probe.services.common.model.Location;

public class LiveLog extends LiveInstrument {

    private final String logFormat;
    private final String[] logArguments;

    public LiveLog(String id, Location location, Expression expression, int hitLimit, HitThrottle throttle,
                   Long expiresAt, String logFormat, String... logArguments) {
        super(id, location, expression, hitLimit, throttle, expiresAt);
        this.logFormat = logFormat;
        this.logArguments = logArguments;
    }

    public String getLogFormat() {
        return logFormat;
    }

    public String[] getLogArguments() {
        return logArguments;
    }
}
