package spp.probe.control;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper;
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer;
import org.apache.skywalking.apm.agent.core.plugin.WitnessFinder;
import spp.probe.SourceProbe;
import spp.protocol.platform.PlatformAddress;
import spp.protocol.probe.command.LiveInstrumentCommand;
import spp.protocol.probe.command.LiveInstrumentContext;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static spp.probe.SourceProbe.instrumentation;
import static spp.protocol.probe.ProbeAddress.LIVE_LOG_REMOTE;

public class LiveLogRemote extends AbstractVerticle {

    private static final BiConsumer<String, String> EVENT_CONSUMER = (address, json) -> FrameHelper.sendFrame(
            BridgeEventType.PUBLISH.name().toLowerCase(), address, new JsonObject(json), SourceProbe.tcpSocket
    );

    private Method getLogs;
    private Method addLog;
    private Method removeLog;
    private static Method isLogEnabled;
    private static Method putLocalVariable;
    private static Method putField;
    private static Method putStaticField;
    private static Method isHit;
    private static Method putLog;

    @Override
    public void start() {
        try {
            ClassLoader agentClassLoader = (ClassLoader) Class.forName("org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader")
                    .getMethod("getDefault").invoke(null);
            Class serviceClass = Class.forName(
                    "spp.probe.services.impl.log.LiveLogService", false, agentClassLoader);
            Field poolMapField = WitnessFinder.class.getDeclaredField("poolMap");
            poolMapField.setAccessible(true);
            serviceClass.getMethod("setPoolMap", Map.class).invoke(null, poolMapField.get(WitnessFinder.INSTANCE));
            serviceClass.getMethod("setLogEventConsumer", BiConsumer.class).invoke(null, EVENT_CONSUMER);
            serviceClass.getMethod("setInstrumentation", Instrumentation.class).invoke(null, instrumentation);

            getLogs = serviceClass.getMethod("getLogs");
            addLog = serviceClass.getMethod("addLog",
                    String.class, String.class, String[].class, String.class, int.class,
                    String.class, int.class, int.class, String.class, Long.class, boolean.class);
            removeLog = serviceClass.getMethod("removeLog",
                    String.class, int.class, String.class);
            isLogEnabled = serviceClass.getMethod("isLogEnabled", String.class);
            isHit = serviceClass.getMethod("isHit", String.class);

            Class contextClass = Class.forName(
                    "spp.probe.services.common.ContextReceiver", false, agentClassLoader);
            putLocalVariable = contextClass.getMethod("putLocalVariable",
                    String.class, String.class, Object.class);
            putField = contextClass.getMethod("putField",
                    String.class, String.class, Object.class);
            putStaticField = contextClass.getMethod("putStaticField",
                    String.class, String.class, Object.class);
            putLog = contextClass.getMethod("putLog",
                    String.class, String.class, String[].class);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        vertx.eventBus().<JsonObject>localConsumer("local." + LIVE_LOG_REMOTE.address).handler(it -> {
            try {
                LiveInstrumentCommand command = LiveInstrumentCommand.fromJson(it.body().toString());
                switch (command.getCommandType()) {
                    case GET_LIVE_INSTRUMENTS:
                        getLogs();
                        break;
                    case ADD_LIVE_INSTRUMENT:
                        addLog(command);
                        break;
                    case REMOVE_LIVE_INSTRUMENT:
                        removeLog(command);
                        break;
                }
            } catch (InvocationTargetException ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                if (ex.getCause() != null) {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getCause(), 4000));
                } else {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getTargetException(), 4000));
                }

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_LOG_REMOVED.address,
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            } catch (Throwable ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_LOG_REMOVED.address,
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            }
        });
    }

    private void getLogs() throws Exception {
        LiveInstrumentCommand.Response response = new LiveInstrumentCommand.Response();
        response.setTimestamp(System.currentTimeMillis());
        response.setSuccess(true);
        response.setContext(new LiveInstrumentContext().addLiveInstruments((List<String>) getLogs.invoke(null)));

        FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name().toLowerCase(),
                PlatformAddress.LIVE_LOGS.address,
                JsonObject.mapFrom(response), SourceProbe.tcpSocket
        );
    }

    private void addLog(LiveInstrumentCommand command) throws Exception {
        String logData = command.getContext().getLiveInstruments().get(0);
        JsonObject logObject = new JsonObject(logData);
        String id = logObject.getString("id");
        String logFormat = logObject.getString("logFormat");
        Object[] objectArray = logObject.getJsonArray("logArguments").getList().toArray();
        String[] logArguments = Arrays.copyOf(objectArray, objectArray.length, String[].class);
        JsonObject location = logObject.getJsonObject("location");
        String source = location.getString("source");
        int line = location.getInteger("line");
        int hitLimit = logObject.getInteger("hitLimit");
        String condition = logObject.getString("condition");
        boolean applyImmediately = logObject.getBoolean("applyImmediately");
        JsonObject throttle = logObject.getJsonObject("throttle");
        int throttleLimit = throttle.getInteger("limit");
        String throttleStep = throttle.getString("step");
        Long expiresAt = logObject.getLong("expiresAt");
        addLog.invoke(null, id, logFormat, logArguments, source, line, condition,
                hitLimit, throttleLimit, throttleStep, expiresAt, applyImmediately);
    }

    private void removeLog(LiveInstrumentCommand command) throws Exception {
        for (String logData : command.getContext().getLiveInstruments()) {
            JsonObject logObject = new JsonObject(logData);
            String logId = logObject.getString("id");
            JsonObject location = logObject.getJsonObject("location");
            String source = location.getString("source");
            int line = location.getInteger("line");
            removeLog.invoke(null, source, line, logId);
        }
        for (String locationData : command.getContext().getLocations()) {
            JsonObject location = new JsonObject(locationData);
            String source = location.getString("source");
            int line = location.getInteger("line");
            removeLog.invoke(null, source, line, null);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isLogEnabled(String logId) {
        try {
            return (Boolean) isLogEnabled.invoke(null, logId);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String logId) {
        try {
            return (Boolean) isHit.invoke(null, logId);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static void putLog(String logId, String logFormat, String... logArguments) {
        try {
            putLog.invoke(null, logId, logFormat, logArguments);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putLocalVariable(String logId, String key, Object value) {
        try {
            putLocalVariable.invoke(null, logId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putField(String logId, String key, Object value) {
        try {
            putField.invoke(null, logId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putStaticField(String logId, String key, Object value) {
        try {
            putStaticField.invoke(null, logId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
