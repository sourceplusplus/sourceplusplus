package spp.probe.services.impl.log;

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
import spp.probe.services.impl.log.model.LiveLog;
import spp.protocol.probe.error.LiveInstrumentException;
import spp.protocol.probe.error.LiveInstrumentException.ErrorType;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static spp.protocol.platform.PlatformAddress.LIVE_LOG_APPLIED;
import static spp.protocol.platform.PlatformAddress.LIVE_LOG_REMOVED;

public class LiveLogService {

    private static final Map<String, LiveLog> logs = new ConcurrentHashMap<>();
    private static final Map<String, LiveLog> applyingLogs = new ConcurrentHashMap<>();
    private final static SpelExpressionParser parser = new SpelExpressionParser(
            new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, LiveLogService.class.getClassLoader()));
    private static BiConsumer<String, String> logEventConsumer;
    private static Map<ClassLoader, TypePool> poolMap = new HashMap<>();
    private static final Timer timer = new Timer("LiveLogScheduler", true);
    private static Instrumentation instrumentation;

    static {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                List<LiveLog> removeLogs = new ArrayList<>();
                logs.values().forEach(it -> {
                    if (it.getExpiresAt() != null && System.currentTimeMillis() >= it.getExpiresAt()) {
                        removeLogs.add(it);
                    }
                });
                applyingLogs.values().forEach(it -> {
                    if (it.getExpiresAt() != null && System.currentTimeMillis() >= it.getExpiresAt()) {
                        removeLogs.add(it);
                    }
                });
                removeLogs.forEach(it -> _removeLog(it, null));
            }
        }, 5000, 5000);
    }

    private static LiveLogApplier liveLogApplier = (inst, log) -> {
        Class clazz = null;
        for (ClassLoader classLoader : poolMap.keySet()) {
            try {
                clazz = Class.forName(log.getLocation().getSource(), true, classLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (clazz == null) {
            if (log.isApplyImmediately()) {
                throw new LiveInstrumentException(ErrorType.CLASS_NOT_FOUND, log.getLocation().getSource()
                ).toEventBusException();
            } else if (!log.isRemoval()) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        liveLogApplier.apply(inst, log);
                    }
                }, 5000);
            }
            return;
        }

        ClassFileTransformer transformer = new LiveTransformer(log.getLocation().getSource());
        try {
            if (!log.isRemoval()) {
                applyingLogs.put(log.getId(), log);
            }
            inst.addTransformer(transformer, true);
            inst.retransformClasses(clazz);
            log.setLive(true);
            if (!log.isRemoval()) {
                logEventConsumer.accept(LIVE_LOG_APPLIED.getAddress(), log.toJson());
            }
        } catch (Throwable ex) {
            //remove and re-transform
            _removeLog(log, ex);

            applyingLogs.remove(log.getId());
            inst.addTransformer(transformer, true);
            try {
                inst.retransformClasses(clazz);
            } catch (UnmodifiableClassException e) {
                throw new RuntimeException(e);
            }
        } finally {
            applyingLogs.remove(log.getId());
            inst.removeTransformer(transformer);
        }
    };

    private LiveLogService() {
    }

    @SuppressWarnings("unused")
    public static void setPoolMap(Map poolMap) {
        LiveLogService.poolMap = poolMap;
    }

    @SuppressWarnings("unused")
    public static void setLogEventConsumer(BiConsumer logEventConsumer) {
        LiveLogService.logEventConsumer = logEventConsumer;
    }

    public static void setLogApplier(LiveLogApplier liveBreakpointApplier) {
        LiveLogService.liveLogApplier = liveBreakpointApplier;
    }

    public static void setInstrumentation(Instrumentation instrumentation) {
        LiveLogService.instrumentation = instrumentation;
    }

    public static Map<String, LiveLog> getLogsMap() {
        return new HashMap<>(logs);
    }

    public static void clearAll() {
        logs.clear();
        applyingLogs.clear();
    }

    @SuppressWarnings("unused")
    public static String addLog(String id, String logFormat, String[] logArguments, String source, int line,
                                String condition, int hitLimit, int throttleLimit, String throttleStep,
                                Long expiresAt, boolean applyImmediately) {
        Location location = new Location(source, line);
        List<LiveLog> existingLogs = getLogs(location);
        Optional<LiveLog> oldLog = existingLogs.stream().filter(it -> source.equals(it.getLocation().getSource()) &&
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

            liveLogApplier.apply(instrumentation, log);
            logs.put(log.getId(), log);
            return log.toJson();
        }
    }

    @SuppressWarnings("unused")
    public static Collection<String> removeLog(String source, int line, String logId) {
        if (logId != null) {
            LiveLog removedLog = logs.remove(logId);
            if (removedLog != null) {
                removedLog.setRemoval(true);
                if (removedLog.isLive()) {
                    liveLogApplier.apply(instrumentation, removedLog);
                    return Collections.singletonList(removedLog.toJson());
                }
            }
        } else {
            List<String> removedLogs = new ArrayList<>();
            getLogs(new Location(source, line)).forEach(it -> {
                LiveLog removedLog = logs.remove(it.getId());

                if (removedLog != null) {
                    removedLog.setRemoval(true);
                    if (removedLog.isLive()) {
                        liveLogApplier.apply(instrumentation, removedLog);
                        removedLogs.add(removedLog.toJson());
                    }
                }
            });
            return removedLogs;
        }
        return Collections.EMPTY_LIST;
    }

    public static void _removeLog(LiveLog log, Throwable ex) {
        removeLog(log.getLocation().getSource(), log.getLocation().getLine(), log.getId());
        if (logEventConsumer != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("log", log.toJson());
            map.put("occurredAt", System.currentTimeMillis());
            if (ex != null) {
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));
            }
            logEventConsumer.accept(LIVE_LOG_REMOVED.getAddress(), ModelSerializer.INSTANCE.toJson(map));
        }
    }

    public static List<LiveLog> getLogs(Location location) {
        Set<LiveLog> allLogs = logs.values().stream()
                .filter(it -> it.getLocation().equals(location)).collect(Collectors.toSet());
        allLogs.addAll(applyingLogs.values().stream()
                .filter(it -> it.getLocation().equals(location)).collect(Collectors.toSet()));
        return new ArrayList<>(allLogs);
    }

    @SuppressWarnings("unused")
    public static boolean isLogEnabled(String logId) {
        boolean applied = logs.containsKey(logId);
        if (applied) {
            return true;
        } else {
            return applyingLogs.containsKey(logId);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String logId) {
        LiveLog log = logs.get(logId);
        if (log == null) {
            return false;
        }

        if (log.getThrottle().isRateLimited()) {
            ContextReceiver.clear(logId);
            return false;
        }

        if (log.getExpression() == null) {
            if (log.isFinished()) {
                _removeLog(log, null);
            }
            return true;
        }

        try {
            if (evaluateCondition(log)) {
                if (log.isFinished()) {
                    _removeLog(log, null);
                }
                return true;
            } else {
                ContextReceiver.clear(logId);
                return false;
            }
        } catch (Throwable e) {
            ContextReceiver.clear(logId);
            _removeLog(log, e);
            return false;
        }
    }

    private static boolean evaluateCondition(LiveLog liveLog) {
        ContextMap rootObject = ContextReceiver.get(liveLog.getId());
        StandardEvaluationContext context = new StandardEvaluationContext(rootObject);
        return liveLog.getExpression().getValue(context, Boolean.class);
    }
}
