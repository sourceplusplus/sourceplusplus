package spp.probe.services.common.transform;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.*;
import spp.probe.services.common.model.ClassField;
import spp.probe.services.common.model.ClassMetadata;
import spp.probe.services.common.model.LocalVariable;

import java.util.HashMap;
import java.util.Map;

public class MetadataCollector extends ClassVisitor {

    private static final int ASM_VERSION = Opcodes.ASM7;
    private final ClassMetadata classMetadata;

    public MetadataCollector(final ClassMetadata classMetadata) {
        super(ASM_VERSION);
        this.classMetadata = classMetadata;
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        classMetadata.addField(new ClassField(access, name, desc));
        return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String methodName, String desc, String signature,
                                     String[] exceptions) {
        MethodVisitor superMV = super.visitMethod(access, methodName, desc, signature, exceptions);

        final String methodUniqueName = methodName + desc;
        return new MethodVisitor(ASM_VERSION, superMV) {
            private final Map<String, Integer> labelLineMapping = new HashMap<>();

            @Override
            public void visitLineNumber(int line, Label start) {
                labelLineMapping.put(start.toString(), line);
            }

            @Override
            public void visitLocalVariable(String name, String desc, String signature,
                                           Label start, Label end, int index) {
                super.visitLocalVariable(name, desc, signature, start, end, index);
                classMetadata.addVariable(
                        methodUniqueName,
                        new LocalVariable(name, desc, labelLine(start), labelLine(end), index)
                );
            }

            private int labelLine(Label label) {
                String labelId = label.toString();
                if (labelLineMapping.containsKey(labelId)) {
                    return labelLineMapping.get(labelId);
                }
                return Integer.MAX_VALUE;
            }
        };
    }
}
