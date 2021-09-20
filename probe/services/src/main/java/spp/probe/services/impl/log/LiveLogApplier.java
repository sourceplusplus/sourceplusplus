package spp.probe.services.impl.log;

import spp.probe.services.impl.log.model.LiveLog;

import java.lang.instrument.Instrumentation;

public interface LiveLogApplier {

    void apply(Instrumentation inst, LiveLog log);
}
