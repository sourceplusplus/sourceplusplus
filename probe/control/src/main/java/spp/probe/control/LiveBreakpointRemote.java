package spp.probe.control;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.json.Json;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static spp.probe.SourceProbe.instrumentation;
import static spp.protocol.probe.ProbeAddress.LIVE_BREAKPOINT_REMOTE;

public class LiveBreakpointRemote extends AbstractVerticle {

    private static final BiConsumer<String, String> EVENT_CONSUMER = (address, json) -> FrameHelper.sendFrame(
            BridgeEventType.PUBLISH.name().toLowerCase(), address, new JsonObject(json), SourceProbe.tcpSocket
    );

    private Method getBreakpoints;
    private Method addBreakpoint;
    private Method removeBreakpoint;
    private static Method isBreakpointEnabled;
    private static Method putLocalVariable;
    private static Method putField;
    private static Method putStaticField;
    private static Method isHit;
    private static Method putBreakpoint;

    @Override
    public void start() {
        try {
            ClassLoader agentClassLoader = (ClassLoader) Class.forName("org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader")
                    .getMethod("getDefault").invoke(null);
            Class serviceClass = Class.forName(
                    "spp.probe.services.impl.breakpoint.LiveBreakpointService", false, agentClassLoader);
            Field poolMapField = WitnessFinder.class.getDeclaredField("poolMap");
            poolMapField.setAccessible(true);
            serviceClass.getMethod("setPoolMap", Map.class).invoke(null, poolMapField.get(WitnessFinder.INSTANCE));
            serviceClass.getMethod("setBreakpointEventConsumer", BiConsumer.class).invoke(null, EVENT_CONSUMER);
            serviceClass.getMethod("setInstrumentation", Instrumentation.class).invoke(null, instrumentation);

            getBreakpoints = serviceClass.getMethod("getBreakpoints");
            addBreakpoint = serviceClass.getMethod("addBreakpoint",
                    String.class, String.class, int.class, String.class, int.class,
                    int.class, String.class, Long.class, boolean.class);
            removeBreakpoint = serviceClass.getMethod("removeBreakpoint",
                    String.class, int.class, String.class);
            isBreakpointEnabled = serviceClass.getMethod("isBreakpointEnabled", String.class);
            isHit = serviceClass.getMethod("isHit", String.class);

            Class contextClass = Class.forName(
                    "spp.probe.services.common.ContextReceiver", false, agentClassLoader);
            putLocalVariable = contextClass.getMethod("putLocalVariable",
                    String.class, String.class, Object.class);
            putField = contextClass.getMethod("putField",
                    String.class, String.class, Object.class);
            putStaticField = contextClass.getMethod("putStaticField",
                    String.class, String.class, Object.class);
            putBreakpoint = contextClass.getMethod("putBreakpoint",
                    String.class, String.class, int.class, Throwable.class);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        vertx.eventBus().<JsonObject>localConsumer("local." + LIVE_BREAKPOINT_REMOTE.getAddress()).handler(it -> {
            try {
                LiveInstrumentCommand command = Json.decodeValue(it.body().toString(), LiveInstrumentCommand.class);
                switch (command.getCommandType()) {
                    case GET_LIVE_INSTRUMENTS:
                        getBreakpoints();
                        break;
                    case ADD_LIVE_INSTRUMENT:
                        addBreakpoint(command);
                        break;
                    case REMOVE_LIVE_INSTRUMENT:
                        removeBreakpoint(command);
                        break;
                }
            } catch (InvocationTargetException ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                if (ex.getCause() != null) {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getCause(), 4000));
                } else {
                    map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex.getTargetException(), 4000));
                }

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_BREAKPOINT_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            } catch (Throwable ex) {
                Map<String, Object> map = new HashMap<>();
                map.put("command", it.body().toString());
                map.put("occurredAt", System.currentTimeMillis());
                map.put("cause", ThrowableTransformer.INSTANCE.convert2String(ex, 4000));

                FrameHelper.sendFrame(
                        BridgeEventType.PUBLISH.name().toLowerCase(),
                        PlatformAddress.LIVE_BREAKPOINT_REMOVED.getAddress(),
                        JsonObject.mapFrom(map), SourceProbe.tcpSocket
                );
            }
        });
    }

    private void getBreakpoints() throws Exception {
        LiveInstrumentCommand.Response response = new LiveInstrumentCommand.Response(
                true, null, System.currentTimeMillis(),
                new LiveInstrumentContext().addLiveInstruments((List<String>) getBreakpoints.invoke(null))
        );

        FrameHelper.sendFrame(
                BridgeEventType.PUBLISH.name().toLowerCase(),
                PlatformAddress.LIVE_BREAKPOINTS.getAddress(),
                JsonObject.mapFrom(response), SourceProbe.tcpSocket
        );
    }

    private void addBreakpoint(LiveInstrumentCommand command) throws Exception {
        String breakpointData = command.getContext().getLiveInstruments().get(0);
        JsonObject breakpointObject = new JsonObject(breakpointData);
        String id = breakpointObject.getString("id");
        JsonObject location = breakpointObject.getJsonObject("location");
        String source = location.getString("source");
        int line = location.getInteger("line");
        int hitLimit = breakpointObject.getInteger("hitLimit");
        String condition = breakpointObject.getString("condition");
        boolean applyImmediately = breakpointObject.getBoolean("applyImmediately");
        JsonObject throttle = breakpointObject.getJsonObject("throttle");
        int throttleLimit = throttle.getInteger("limit");
        String throttleStep = throttle.getString("step");
        Long expiresAt = breakpointObject.getLong("expiresAt");
        addBreakpoint.invoke(null, id, source, line, condition, hitLimit,
                throttleLimit, throttleStep, expiresAt, applyImmediately);
    }

    private void removeBreakpoint(LiveInstrumentCommand command) throws Exception {
        for (String breakpointData : command.getContext().getLiveInstruments()) {
            JsonObject breakpointObject = new JsonObject(breakpointData);
            String breakpointId = breakpointObject.getString("id");
            JsonObject location = breakpointObject.getJsonObject("location");
            String source = location.getString("source");
            int line = location.getInteger("line");
            removeBreakpoint.invoke(null, source, line, breakpointId);
        }
        for (String locationData : command.getContext().getLocations()) {
            JsonObject location = new JsonObject(locationData);
            String source = location.getString("source");
            int line = location.getInteger("line");
            removeBreakpoint.invoke(null, source, line, null);
        }
    }

    @SuppressWarnings("unused")
    public static boolean isBreakpointEnabled(String breakpointId) {
        try {
            return (Boolean) isBreakpointEnabled.invoke(null, breakpointId);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static boolean isHit(String breakpointId) {
        try {
            return (Boolean) isHit.invoke(null, breakpointId);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("unused")
    public static void putBreakpoint(String breakpointId, String source, int line, Throwable ex) {
        try {
            putBreakpoint.invoke(null, breakpointId, source, line, ex);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putLocalVariable(String breakpointId, String key, Object value) {
        try {
            putLocalVariable.invoke(null, breakpointId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putField(String breakpointId, String key, Object value) {
        try {
            putField.invoke(null, breakpointId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    public static void putStaticField(String breakpointId, String key, Object value) {
        try {
            putStaticField.invoke(null, breakpointId, key, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
