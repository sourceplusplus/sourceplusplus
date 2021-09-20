package spp.probe.services.impl.breakpoint;

import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.pool.TypePool;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import spp.probe.services.common.ContextMap;
import spp.probe.services.common.ContextReceiver;
import spp.probe.services.common.ModelSerializer;
import spp.probe.services.common.model.HitThrottle;
import spp.probe.services.common.model.HitThrottleStep;
import spp.probe.services.common.model.Location;
import spp.probe.services.common.transform.LiveTransformer;
import spp.probe.services.impl.breakpoint.model.LiveBreakpoint;
import spp.protocol.probe.error.LiveInstrumentException;
import spp.protocol.probe.error.LiveInstrumentException.ErrorType;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static spp.protocol.platform.PlatformAddress.LIVE_BREAKPOINT_APPLIED;
import static spp.protocol.platform.PlatformAddress.LIVE_BREAKPOINT_REMOVED;

public class LiveBreakpointService {

    private static final Map<String, LiveBreakpoint> breakpoints = new ConcurrentHashMap<>();
    private static final Map<String, LiveBreakpoint> applyingBreakpoints = new ConcurrentHashMap<>();
    private final static SpelExpressionParser parser = new SpelExpressionParser(
            new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveBreakpointService.class.getClassLoader()));
    private static BiConsumer<String, String> breakpointEventConsumer;
    private static Map<ClassLoader, TypePool> poolMap = new HashMap<>();
    private static final Timer timer = new Timer("LiveBreakpointScheduler", true);
    private static Instrumentation instrumentation;

    static {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                List<LiveBreakpoint> removeBreakpoints = new ArrayList<>();
                breakpoints.values().forEach(it -> {
                    if (it.getExpiresAt() != null && System.currentTimeMillis() >= it.getExpiresAt()) {
                        removeBreakpoints.add(it);
                    }
                });
                applyingBreakpoints.values().forEach(it -> {
                    if (it.getExpiresAt() != null && System.currentTimeMillis() >= it.getExpiresAt()) {
                        removeBreakpoints.add(it);
                    }
                });
                removeBreakpoints.forEach(it -> _removeBreakpoint(it, null));
            }
        }, 5000, 5000);
    }

    public static LiveBreakpointApplier liveBreakpointApplier = (inst, breakpoint) -> {
        Class clazz = null;
        for (ClassLoader classLoader : poolMap.keySet()) {
            try {
                clazz = Class.forName(breakpoint.getLocation().getSource(), true, classLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (clazz == null) {
            if (breakpoint.isApplyImmediately()) {
                throw new LiveInstrumentException(ErrorType.CLASS_NOT_FOUND, breakpoint.getLocation().getSource()
                ).toEventBusException();
            } else if (!breakpoint.isRemoval()) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        liveBreakpointApplier.apply(inst, breakpoint);
                    }
                }, 5000);
            }
            return;
        }

        ClassFileTransformer transformer = new LiveTransformer(breakpoint.getLocation().getSource());
        try {
            if (!breakpoint.isRemoval()) {
                applyingBreakpoints.put(breakpoint.getId(), breakpoint);
            }
            inst.addTransformer(transformer, true);
            inst.retransformClasses(clazz);
            breakpoint.setLive(true);
            if (!breakpoint.isRemoval()) {
                breakpointEventConsumer.accept(LIVE_BREAKPOINT_APPLIED.address, breakpoint.toJson());
            }
        } catch (Throwable ex) {
            //remove and re-transform
            _removeBreakpoint(breakpoint, ex);

            applyingBreakpoints.remove(breakpoint.getId());
            inst.addTransformer(transformer, true);
            try {
                inst.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
                throw new RuntimeException(e);
            }
        } finally {
            applyingBreakpoints.remove(breakpoint.getId());
            inst.removeTransformer(transformer);
        }
    };

    private LiveBreakpointService() {
    }

    @SuppressWarnings("unused")
    public static void setPoolMap(Map poolMap) {
        LiveBreakpointService.poolMap = poolMap;
    }

    @SuppressWarnings("unused")
    public static void setBreakpointEventConsumer(BiConsumer breakpointEventConsumer) {
        LiveBreakpointService.breakpointEventConsumer = breakpointEventConsumer;
    }

    public static void setBreakpointApplier(LiveBreakpointApplier liveBreakpointApplier) {
        LiveBreakpointService.liveBreakpointApplier = liveBreakpointApplier;
    }

    public static void setInstrumentation(Instrumentation instrumentation) {
        LiveBreakpointService.instrumentation = instrumentation;
    }

    public static Map<String, LiveBreakpoint> getBreakpointsMap() {
        return new HashMap<>(breakpoints);
    }

    public static void clearAll() {
        breakpoints.clear();
        applyingBreakpoints.clear();
    }

    @SuppressWarnings("unused")
    public static List<String> getBreakpoints() {
        return breakpoints.values().stream().map(LiveBreakpoint::toJson).collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    public static String addBreakpoint(String id, String source, int line, String condition, int hitLimit,
                                       int throttleLimit, String throttleStep, Long expiresAt, boolean applyImmediately) {
        Location location = new Location(source, line);
        List<LiveBreakpoint> existingBreakpoints = getBreakpoints(location);
        Optional<LiveBreakpoint> oldBreakpoint = existingBreakpoints.stream().filter(it -> source.equals(it.getLocation().getSource()) &&
                line == it.getLocation().getLine() &&
                (condition == null && it.getExpression() == null ||
                        (condition != null && it.getExpression() != null && condition.equals(it.getExpression().getExpressionString())))).findAny();
        if (oldBreakpoint.isPresent()) {
            return oldBreakpoint.get().toJson();
        } else {
            HitThrottle throttle = new HitThrottle(throttleLimit, HitThrottleStep.valueOf(throttleStep));
            LiveBreakpoint breakpoint;
            if (condition != null && condition.length() > 0) {
                try {
                    Expression expression = parser.parseExpression(condition);
                    breakpoint = new LiveBreakpoint(id, location, expression, hitLimit, throttle, expiresAt);
                } catch (ParseException ex) {
                    throw new LiveInstrumentException(ErrorType.CONDITIONAL_FAILED, ex.getMessage())
                            .toEventBusException();
                }
            } else {
                breakpoint = new LiveBreakpoint(id, location, null, hitLimit, throttle, expiresAt);
            }
            breakpoint.setApplyImmediately(applyImmediately);

            liveBreakpointApplier.apply(instrumentation, breakpoint);
            breakpoints.put(breakpoint.getId(), breakpoint);
            return breakpoint.toJson();
        }
    }

    @SuppressWarnings("unused")
    public static Collection<String> removeBreakpoint(String source, int line, String breakpointId) {
        if (breakpointId != null) {
            LiveBreakpoint removedBreakpoint = breakpoints.remove(breakpointId);
            if (removedBreakpoint != null) {
                removedBreakpoint.setRemoval(true);
                if (removedBreakpoint.isLive()) {
                    liveBreakpointApplier.apply(instrumentation, removedBreakpoint);
                    return Collections.singletonList(removedBreakpoint.toJson());
                }
            }
        } else {
            List<String> removedBreakpoints = new ArrayList<>();
            getBreakpoints(new Location(source, line)).forEach(it -> {
                LiveBreakpoint removedBreakpoint = breakpoints.remove(it.getId());

                if (removedBreakpoint != null) {
                    removedBreakpoint.setRemoval(true);
                    if (removedBreakpoint.isLive()) {
                        liveBreakpointApplier.apply(instrumentation, removedBreakpoint);
                        removedBreakpoints.add(removedBreakpoint.toJson());
                    }
                }
            });
            return removedBreakpoints;
        }
        return Collections.EMPTY_LIST;
    }

    public static void _removeBreakpoint(LiveBreakpoint breakpoint, Throwable ex) {
        removeBreakpoint(breakpoint.getLocation().getSource(), breakpoint.getLocation().getLine(), breakpoint.getId());
        if (breakpointEventConsumer != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("breakpoint", breakpoint.toJson());
            if (ex != null) {
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));
            }
            breakpointEventConsumer.accept(LIVE_BREAKPOINT_REMOVED.address, ModelSerializer.INSTANCE.toJson(map));
        }
    }

    public static List<LiveBreakpoint> getBreakpoints(Location location) {
        Set<LiveBreakpoint> bps = breakpoints.values().stream()
                .filter(it -> it.getLocation().equals(location)).collect(Collectors.toSet());
        bps.addAll(applyingBreakpoints.values().stream()
                .filter(it -> it.getLocation().equals(location)).collect(Collectors.toSet()));
        return new ArrayList<>(bps);
    }

    @SuppressWarnings("unused")
    public static boolean isBreakpointEnabled(String breakpointId) {
        boolean applied = breakpoints.containsKey(breakpointId);
        if (applied) {
            return true;
        } else {
            return applyingBreakpoints.containsKey(breakpointId);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String breakpointId) {
        LiveBreakpoint breakpoint = breakpoints.get(breakpointId);
        if (breakpoint == null) {
            return false;
        }

        if (breakpoint.getThrottle().isRateLimited()) {
            ContextReceiver.clear(breakpointId);
            return false;
        }

        if (breakpoint.getExpression() == null) {
            if (breakpoint.isFinished()) {
                _removeBreakpoint(breakpoint, null);
            }
            return true;
        }

        try {
            if (evaluateCondition(breakpoint)) {
                if (breakpoint.isFinished()) {
                    _removeBreakpoint(breakpoint, null);
                }
                return true;
            } else {
                ContextReceiver.clear(breakpointId);
                return false;
            }
        } catch (Throwable e) {
            ContextReceiver.clear(breakpointId);
            _removeBreakpoint(breakpoint, e);
            return false;
        }
    }

    private static boolean evaluateCondition(LiveBreakpoint liveBreakpoint) {
        ContextMap rootObject = ContextReceiver.get(liveBreakpoint.getId());
        StandardEvaluationContext context = new StandardEvaluationContext(rootObject);
        return liveBreakpoint.getExpression().getValue(context, Boolean.class);
    }
}
