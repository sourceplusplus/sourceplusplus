package spp.probe.services.common.model;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClassMetadata implements Serializable {

    private final List<ClassField> fields;
    private final List<ClassField> staticFields;
    private final Map<String, List<LocalVariable>> variables;

    public ClassMetadata() {
        fields = new ArrayList<>();
        staticFields = new ArrayList<>();
        variables = new HashMap<>();
    }

    public void addField(ClassField field) {
        if (isStaticField(field)) {
            staticFields.add(field);
        } else {
            fields.add(field);
        }
    }

    public void addVariable(String methodId, LocalVariable variable) {
        variables.computeIfAbsent(methodId, k -> new ArrayList<>());
        variables.get(methodId).add(variable);
    }

    public List<ClassField> getFields() {
        return fields;
    }

    public List<ClassField> getStaticFields() {
        return staticFields;
    }

    public Map<String, List<LocalVariable>> getVariables() {
        return variables;
    }

    private boolean isStaticField(ClassField field) {
        return (field.getAccess() & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public String toString() {
        return "ClassMetadata{" +
                "fields=" + fields +
                ", staticFields=" + staticFields +
                ", variables=" + variables +
                '}';
    }
}
