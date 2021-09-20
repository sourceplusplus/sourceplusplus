package spp.probe.services.common;

import java.util.Map;

public class ContextMap {

    private Map<String, Object> localVariables;
    private Map<String, Object> fields;
    private Map<String, Object> staticFields;

    @SuppressWarnings("unused")
    public Map<String, Object> getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(Map<String, Object> localVariables) {
        this.localVariables = localVariables;
    }

    @SuppressWarnings("unused")
    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields;
    }

    @SuppressWarnings("unused")
    public Map<String, Object> getStaticFields() {
        return staticFields;
    }

    public void setStaticFields(Map<String, Object> staticFields) {
        this.staticFields = staticFields;
    }

    @Override
    public String toString() {
        return "ContextMap{" +
                "localVariables=" + localVariables +
                ", fields=" + fields +
                ", staticFields=" + staticFields +
                '}';
    }
}
