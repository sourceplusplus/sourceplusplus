package spp.probe.services.common;

import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.StringTag;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.util.ThrowableTransformer;
import org.apache.skywalking.apm.agent.core.remote.LogReportServiceClient;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.logging.v3.*;
import spp.probe.services.common.model.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ContextReceiver {

    private static final Pattern ignoredVariables = Pattern.compile("(_\\$EnhancedClassField_ws)|((delegate|cachedValue)\\$[a-zA-Z0-9$]+)");
    private static final Map<String, Map<String, Object>> localVariables = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> fields = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Object>> staticFields = new ConcurrentHashMap<>();
    private static final LogReportServiceClient logReport = ServiceManager.INSTANCE.findService(LogReportServiceClient.class);

    public static ContextMap get(String instrumentId) {
        ContextMap contextMap = new ContextMap();
        contextMap.setFields(fields.get(instrumentId));
        contextMap.setLocalVariables(localVariables.get(instrumentId));
        contextMap.setStaticFields(staticFields.get(instrumentId));
        return contextMap;
    }

    public static void clear(String instrumentId) {
        fields.remove(instrumentId);
        localVariables.remove(instrumentId);
        staticFields.remove(instrumentId);
    }

    @SuppressWarnings("unused")
    public static void putLocalVariable(String instrumentId, String key, Object value) {
        addInstrumentVariable(instrumentId, key, value, localVariables);
    }

    @SuppressWarnings("unused")
    public static void putField(String instrumentId, String key, Object value) {
        addInstrumentVariable(instrumentId, key, value, fields);
    }

    @SuppressWarnings("unused")
    public static void putStaticField(String instrumentId, String key, Object value) {
        addInstrumentVariable(instrumentId, key, value, staticFields);
    }

    private static void addInstrumentVariable(String instrumentId, String key, Object value,
                                              Map<String, Map<String, Object>> variableMap) {
        if (value == null) {
            return;
        } else if (ignoredVariables.matcher(key).matches()) {
            return;
        }

        variableMap.computeIfAbsent(instrumentId, it -> new HashMap<>()).put(key, value);
    }

    @SuppressWarnings("unused")
    public static void putBreakpoint(String breakpointId, String source, int line, Throwable throwable) {
        AbstractSpan activeSpan = ContextManager.createLocalSpan(throwable.getStackTrace()[0].toString());

        Map<String, Object> localVars = localVariables.remove(breakpointId);
        if (localVars != null) {
            localVars.forEach((key, value) -> activeSpan.tag(
                    new StringTag("spp.local-variable:" + breakpointId + ":" + key), encodeObject(key, value)));
        }
        Map<String, Object> localFields = fields.remove(breakpointId);
        if (localFields != null) {
            localFields.forEach((key, value) -> activeSpan.tag(
                    new StringTag("spp.field:" + breakpointId + ":" + key), encodeObject(key, value)));
        }
        Map<String, Object> localStaticFields = staticFields.remove(breakpointId);
        if (localStaticFields != null) {
            localStaticFields.forEach((key, value) -> activeSpan.tag(
                    new StringTag("spp.static-field:" + breakpointId + ":" + key), encodeObject(key, value)));
        }
        activeSpan.tag(new StringTag("spp.stack-trace:" + breakpointId),
                ThrowableTransformer.INSTANCE.convert2String(throwable, 4000));
        activeSpan.tag(new StringTag("spp.breakpoint:" + breakpointId),
                new Location(source, line).toJson());

        ContextManager.stopSpan(activeSpan);
    }

    @SuppressWarnings("unused")
    public static void putLog(String logId, String logFormat, String... logArguments) {
        Map<String, Object> localVars = localVariables.remove(logId);
        Map<String, Object> localFields = fields.remove(logId);
        Map<String, Object> localStaticFields = staticFields.remove(logId);

        LogTags.Builder logTags = LogTags.newBuilder()
                .addData(KeyStringValuePair.newBuilder()
                        .setKey("log_id").setValue(logId).build())
                .addData(KeyStringValuePair.newBuilder()
                        .setKey("level").setValue("Live").build())
                .addData(KeyStringValuePair.newBuilder()
                        .setKey("thread").setValue(Thread.currentThread().getName()).build());
        if (logArguments.length > 0) {
            for (int i = 0; i < logArguments.length; i++) {
                //todo: is it smarter to pass localVariables[arg]?
                Object argValue = (localVars == null) ? null : localVars.get(logArguments[i]);
                if (argValue == null) {
                    argValue = (localFields == null) ? null : localFields.get(logArguments[i]);
                    if (argValue == null) {
                        argValue = (localStaticFields == null) ? null : localStaticFields.get(logArguments[i]);
                    }
                }
                Object value = Optional.ofNullable(argValue).orElse("null");
                logTags.addData(KeyStringValuePair.newBuilder()
                        .setKey("argument." + i).setValue(value.toString()).build());
            }
        }

        LogData.Builder builder = LogData.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setService(Config.Agent.SERVICE_NAME)
                .setServiceInstance(Config.Agent.INSTANCE_NAME)
                .setTags(logTags.build())
                .setBody(LogDataBody.newBuilder().setType(LogDataBody.ContentCase.TEXT.name())
                        .setText(TextLog.newBuilder().setText(logFormat).build()).build());
        LogData logData = -1 == ContextManager.getSpanId() ? builder.build()
                : builder.setTraceContext(TraceContext.newBuilder()
                .setTraceId(ContextManager.getGlobalTraceId())
                .setSpanId(ContextManager.getSpanId())
                .setTraceSegmentId(ContextManager.getSegmentId())
                .build()).build();
        logReport.produce(logData);
    }

    private static String encodeObject(String varName, Object value) {
        try {
            return String.format("{\"@class\":\"%s\",\"@identity\":\"%s\",\"" + varName + "\":%s}",
                    value.getClass().getName(), Integer.toHexString(System.identityHashCode(value)),
                    ModelSerializer.INSTANCE.toExtendedJson(value));
        } catch (Exception ex) {
            try {
                Map<String, Object> map = new HashMap<>();
                map.put(varName, value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value)));
                map.put("@class", "java.lang.Class");
                map.put("@identity", Integer.toHexString(System.identityHashCode(value)));
                map.put("@ex", ex.getMessage());
                return ModelSerializer.INSTANCE.toJson(map);
            } catch (Exception ignore) {
            }
            return value.toString(); //can't reach here
        }
    }
}
