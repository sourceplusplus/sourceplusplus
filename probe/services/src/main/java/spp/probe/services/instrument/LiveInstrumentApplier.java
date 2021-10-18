package spp.probe.services.instrument;

import spp.probe.services.instrument.model.LiveInstrument;

import java.lang.instrument.Instrumentation;

public interface LiveInstrumentApplier {

    void apply(Instrumentation inst, LiveInstrument instrument);
}
