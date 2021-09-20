package spp.probe.services.common.model;

import java.io.Serializable;

public class ClassField implements Serializable {

    private final int access;
    private final String name;
    private final String desc;

    public ClassField(int access, String name, String desc) {
        this.access = access;
        this.name = name;
        this.desc = desc;
    }

    public int getAccess() {
        return access;
    }

    public String getName() {
        return name;
    }

    public String getDesc() {
        return desc;
    }

    @Override
    public String toString() {
        return "ClassField{" +
                "access=" + access +
                ", name='" + name + '\'' +
                ", desc='" + desc + '\'' +
                '}';
    }
}
