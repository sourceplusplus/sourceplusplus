package spp.probe.services.impl.breakpoint;

import spp.probe.services.impl.breakpoint.model.LiveBreakpoint;

import java.lang.instrument.Instrumentation;

public interface LiveBreakpointApplier {

    void apply(Instrumentation inst, LiveBreakpoint breakpoint);
}
