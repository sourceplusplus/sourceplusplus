package spp.probe.services.instrument;

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
import spp.probe.services.instrument.model.LiveInstrument;
import spp.probe.services.instrument.model.LiveLog;
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

public class LiveInstrumentService {

    private static final Map<String, LiveInstrument> instruments = new ConcurrentHashMap<>();
    private static final Map<String, LiveInstrument> applyingInstruments = new ConcurrentHashMap<>();
    private final static SpelExpressionParser parser = new SpelExpressionParser(
            new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveInstrumentService.class.getClassLoader()));
    private static BiConsumer<String, String> instrumentEventConsumer;
    private static Map<ClassLoader, TypePool> poolMap = new HashMap<>();
    private static final Timer timer = new Timer("LiveInstrumentScheduler", true);
    private static Instrumentation instrumentation;

    static {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                List<LiveInstrument> removeInstruments = new ArrayList<>();
                instruments.values().forEach(it -> {
                    if (it.getExpiresAt() != null && System.currentTimeMillis() >= it.getExpiresAt()) {
                        removeInstruments.add(it);
                    }
                });
                applyingInstruments.values().forEach(it -> {
                    if (it.getExpiresAt() != null && System.currentTimeMillis() >= it.getExpiresAt()) {
                        removeInstruments.add(it);
                    }
                });
                removeInstruments.forEach(it -> _removeBreakpoint(it, null));
            }
        }, 5000, 5000);
    }

    public static LiveInstrumentApplier liveInstrumentApplier = (inst, instrument) -> {
        Class clazz = null;
        for (ClassLoader classLoader : poolMap.keySet()) {
            try {
                clazz = Class.forName(instrument.getLocation().getSource(), true, classLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (clazz == null) {
            if (instrument.isApplyImmediately()) {
                throw new LiveInstrumentException(ErrorType.CLASS_NOT_FOUND, instrument.getLocation().getSource()
                ).toEventBusException();
            } else if (!instrument.isRemoval()) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        liveInstrumentApplier.apply(inst, instrument);
                    }
                }, 5000);
            }
            return;
        }

        ClassFileTransformer transformer = new LiveTransformer(instrument.getLocation().getSource());
        try {
            if (!instrument.isRemoval()) {
                applyingInstruments.put(instrument.getId(), instrument);
            }
            inst.addTransformer(transformer, true);
            inst.retransformClasses(clazz);
            instrument.setLive(true);
            if (!instrument.isRemoval()) {
                instrumentEventConsumer.accept(LIVE_BREAKPOINT_APPLIED.getAddress(), instrument.toJson());
            }
        } catch (Throwable ex) {
            //remove and re-transform
            _removeBreakpoint(instrument, ex);

            applyingInstruments.remove(instrument.getId());
            inst.addTransformer(transformer, true);
            try {
                inst.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
                throw new RuntimeException(e);
            }
        } finally {
            applyingInstruments.remove(instrument.getId());
            inst.removeTransformer(transformer);
        }
    };

    private LiveInstrumentService() {
    }

    @SuppressWarnings("unused")
    public static void setPoolMap(Map poolMap) {
        LiveInstrumentService.poolMap = poolMap;
    }

    @SuppressWarnings("unused")
    public static void setInstrumentEventConsumer(BiConsumer instrumentEventConsumer) {
        LiveInstrumentService.instrumentEventConsumer = instrumentEventConsumer;
    }

    public static void setInstrumentApplier(LiveInstrumentApplier liveInstrumentApplier) {
        LiveInstrumentService.liveInstrumentApplier = liveInstrumentApplier;
    }

    public static void setInstrumentation(Instrumentation instrumentation) {
        LiveInstrumentService.instrumentation = instrumentation;
    }

    public static Map<String, LiveInstrument> getInstrumentsMap() {
        return new HashMap<>(instruments);
    }

    public static void clearAll() {
        instruments.clear();
        applyingInstruments.clear();
    }

    @SuppressWarnings("unused")
    public static String addBreakpoint(String id, String source, int line, String condition, int hitLimit,
                                       int throttleLimit, String throttleStep, Long expiresAt, boolean applyImmediately) {
        Location location = new Location(source, line);
        List<LiveInstrument> existingBreakpoints = getInstruments(location);
        Optional<LiveInstrument> oldBreakpoint = existingBreakpoints.stream().filter(it -> source.equals(it.getLocation().getSource()) &&
                line == it.getLocation().getLine() &&
                (condition == null && it.getExpression() == null ||
                        (condition != null && it.getExpression() != null && condition.equals(it.getExpression().getExpressionString())))).findAny();
        if (oldBreakpoint.isPresent()) {
            return oldBreakpoint.get().toJson();
        } else {
            HitThrottle throttle = new HitThrottle(throttleLimit, HitThrottleStep.valueOf(throttleStep));
            LiveInstrument breakpoint;
            if (condition != null && condition.length() > 0) {
                try {
                    Expression expression = parser.parseExpression(condition);
                    breakpoint = new LiveInstrument(id, location, expression, hitLimit, throttle, expiresAt);
                } catch (ParseException ex) {
                    throw new LiveInstrumentException(ErrorType.CONDITIONAL_FAILED, ex.getMessage())
                            .toEventBusException();
                }
            } else {
                breakpoint = new LiveInstrument(id, location, null, hitLimit, throttle, expiresAt);
            }
            breakpoint.setApplyImmediately(applyImmediately);

            liveInstrumentApplier.apply(instrumentation, breakpoint);
            instruments.put(breakpoint.getId(), breakpoint);
            return breakpoint.toJson();
        }
    }

    @SuppressWarnings("unused")
    public static String addLog(String id, String logFormat, String[] logArguments, String source, int line,
                                String condition, int hitLimit, int throttleLimit, String throttleStep,
                                Long expiresAt, boolean applyImmediately) {
        Location location = new Location(source, line);
        List<LiveInstrument> existingLogs = getInstruments(location);
        Optional<LiveInstrument> oldLog = existingLogs.stream().filter(it -> source.equals(it.getLocation().getSource()) &&
                line == it.getLocation().getLine() &&
                (condition == null && it.getExpression() == null ||
                        (condition != null && it.getExpression() != null && condition.equals(it.getExpression().getExpressionString())))).findAny();
        if (oldLog.isPresent()) {
            return oldLog.get().toJson();
        } else {
            HitThrottle throttle = new HitThrottle(throttleLimit, HitThrottleStep.valueOf(throttleStep));
            LiveLog log;
            if (condition != null && condition.length() > 0) {
                try {
                    Expression expression = parser.parseExpression(condition);
                    log = new LiveLog(id, location, expression, hitLimit, throttle, expiresAt, logFormat, logArguments);
                } catch (ParseException ex) {
                    throw new LiveInstrumentException(ErrorType.CONDITIONAL_FAILED, ex.getMessage())
                            .toEventBusException();
                }
            } else {
                log = new LiveLog(id, location, null, hitLimit, throttle, expiresAt, logFormat, logArguments);
            }
            log.setApplyImmediately(applyImmediately);

            liveInstrumentApplier.apply(instrumentation, log);
            instruments.put(log.getId(), log);
            return log.toJson();
        }
    }

    @SuppressWarnings("unused")
    public static Collection<String> removeInstrument(String source, int line, String instrumentId) {
        if (instrumentId != null) {
            LiveInstrument removedInstrument = instruments.remove(instrumentId);
            if (removedInstrument != null) {
                removedInstrument.setRemoval(true);
                if (removedInstrument.isLive()) {
                    liveInstrumentApplier.apply(instrumentation, removedInstrument);
                    return Collections.singletonList(removedInstrument.toJson());
                }
            }
        } else {
            List<String> removedInstruments = new ArrayList<>();
            getInstruments(new Location(source, line)).forEach(it -> {
                LiveInstrument removedInstrument = instruments.remove(it.getId());

                if (removedInstrument != null) {
                    removedInstrument.setRemoval(true);
                    if (removedInstrument.isLive()) {
                        liveInstrumentApplier.apply(instrumentation, removedInstrument);
                        removedInstruments.add(removedInstrument.toJson());
                    }
                }
            });
            return removedInstruments;
        }
        return Collections.EMPTY_LIST;
    }

    public static void _removeBreakpoint(LiveInstrument breakpoint, Throwable ex) {
        removeInstrument(breakpoint.getLocation().getSource(), breakpoint.getLocation().getLine(), breakpoint.getId());
        if (instrumentEventConsumer != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("breakpoint", breakpoint.toJson());
            map.put("occurredAt", System.currentTimeMillis());
            if (ex != null) {
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));
            }
            instrumentEventConsumer.accept(LIVE_BREAKPOINT_REMOVED.getAddress(), ModelSerializer.INSTANCE.toJson(map));
        }
    }

    public static List<LiveInstrument> getInstruments(Location location) {
        Set<LiveInstrument> instruments = LiveInstrumentService.instruments.values().stream()
                .filter(it -> it.getLocation().equals(location)).collect(Collectors.toSet());
        instruments.addAll(applyingInstruments.values().stream()
                .filter(it -> it.getLocation().equals(location)).collect(Collectors.toSet()));
        return new ArrayList<>(instruments);
    }

    @SuppressWarnings("unused")
    public static boolean isInstrumentEnabled(String instrumentId) {
        boolean applied = instruments.containsKey(instrumentId);
        if (applied) {
            return true;
        } else {
            return applyingInstruments.containsKey(instrumentId);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String instrumentId) {
        LiveInstrument instrument = instruments.get(instrumentId);
        if (instrument == null) {
            return false;
        }

        if (instrument.getThrottle().isRateLimited()) {
            ContextReceiver.clear(instrumentId);
            return false;
        }

        if (instrument.getExpression() == null) {
            if (instrument.isFinished()) {
                _removeBreakpoint(instrument, null);
            }
            return true;
        }

        try {
            if (evaluateCondition(instrument)) {
                if (instrument.isFinished()) {
                    _removeBreakpoint(instrument, null);
                }
                return true;
            } else {
                ContextReceiver.clear(instrumentId);
                return false;
            }
        } catch (Throwable e) {
            ContextReceiver.clear(instrumentId);
            _removeBreakpoint(instrument, e);
            return false;
        }
    }

    private static boolean evaluateCondition(LiveInstrument liveInstrument) {
        ContextMap rootObject = ContextReceiver.get(liveInstrument.getId());
        StandardEvaluationContext context = new StandardEvaluationContext(rootObject);
        return liveInstrument.getExpression().getValue(context, Boolean.class);
    }
}
