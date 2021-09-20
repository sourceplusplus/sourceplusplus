package spp.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;

public final class ProbeConfiguration {

    private static Map<String, Map<String, Object>> rawProperties;
    private static JsonObject localProperties;

    static {
        File localFile = new File("spp-probe.yml");
        try {
            //working directory
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            if (localFile.exists()) {
                rawProperties = mapper.readValue(new FileInputStream(localFile), Map.class);
            }
            //ran through intellij
            localFile = new File(new File(ProbeConfiguration.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).getParent(), "spp-probe.yml");
            if (localFile.exists()) {
                rawProperties = mapper.readValue(new FileInputStream(localFile), Map.class);
            }
            //inside jar
            if (rawProperties == null) {
                rawProperties = mapper.readValue(ProbeConfiguration.class.getResourceAsStream("/spp-probe.yml"), Map.class);
            }
            localProperties = JsonObject.mapFrom(rawProperties);
        } catch (Exception e) {
            System.err.println("Failed to read properties file: " + localFile);
            e.printStackTrace();
            System.exit(-1);
        }
    }

    @SuppressWarnings("unused")
    public static JsonObject getSkywalking() {
        return localProperties.getJsonObject("skywalking");
    }

    public static String getString(String property) {
        return localProperties.getJsonObject("spp").getString(property);
    }

    public static void setString(String property, String value) {
        localProperties.getJsonObject("spp").put(property, value);
    }

    public static int getInteger(String property) {
        return localProperties.getJsonObject("spp").getInteger(property);
    }

    @SuppressWarnings("unused")
    public static void setInteger(String property, int value) {
        localProperties.getJsonObject("spp").put(property, value);
    }

    public static List<String[]> getSkywalkingSettings() {
        List<String[]> settings = toProperties(rawProperties).stream()
                .filter(it -> it[0].startsWith("skywalking."))
                .collect(Collectors.toList());
        if (settings.stream().noneMatch(it -> Objects.equals(it[0], "skywalking.agent.service_name")) ||
                settings.stream().noneMatch(it -> Objects.equals(it[0], "skywalking.collector.backend_service"))) {
            throw new RuntimeException("Missing Apache SkyWalking setup configuration");
        }
        return settings;
    }

    public static List<String[]> getSppSettings() {
        return toProperties(rawProperties).stream()
                .filter(it -> it[0].startsWith("spp."))
                .collect(Collectors.toList());
    }

    public static boolean isNotQuite() {
        String quiteMode = getString("quiet_mode");
        if (quiteMode == null) return false;
        return "false".equalsIgnoreCase(quiteMode);
    }

    public static void setQuietMode(boolean quiet) {
        setString("quiet_mode", Boolean.toString(quiet));
    }

    public static String getSkyWalkingLoggingLevel() {
        JsonObject skywalkingConfig = localProperties.getJsonObject("skywalking");
        JsonObject loggingConfig = (skywalkingConfig == null) ? null : skywalkingConfig.getJsonObject("logging");
        String level = (loggingConfig == null) ? null : loggingConfig.getString("level");
        if (level == null) return "WARN";
        else return level.toUpperCase(Locale.ROOT);
    }

    private static List<String[]> toProperties(Map<String, Map<String, Object>> config) {
        List<String[]> sb = new ArrayList<>();
        for (String key : config.keySet()) {
            sb.addAll(toString(key, config.get(key)));
        }
        return sb;
    }

    private static List<String[]> toString(String key, Object value) {
        List<String[]> values = new ArrayList<>();
        if (value instanceof List) {
            List<Object> lst = (List<Object>) value;
            for (Object val : lst) {
                if (val instanceof Map || val instanceof List) {
                    values.addAll(toString(key, val));
                } else if (val != null) {
                    values.add(new String[]{key, val.toString()});
                }
            }
        } else {
            Map<String, Object> map = (Map<String, Object>) value;
            for (String mapKey : map.keySet()) {
                if (map.get(mapKey) instanceof Map || map.get(mapKey) instanceof List) {
                    values.addAll(toString(key + "." + mapKey, map.get(mapKey)));
                } else {
                    values.add(new String[]{key + "." + mapKey, map.get(mapKey).toString()});
                }
            }
        }
        return values;
    }
}
