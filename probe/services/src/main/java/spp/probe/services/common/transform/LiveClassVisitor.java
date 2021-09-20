package spp.probe.services.common.transform;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.ClassVisitor;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.MethodVisitor;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes;
import spp.probe.services.common.model.ClassMetadata;
import spp.probe.services.impl.breakpoint.LiveBreakpointTransformer;
import spp.probe.services.impl.log.LiveLogTransformer;

public class LiveClassVisitor extends ClassVisitor {

    private final String source;
    private final ClassMetadata classMetadata;
    private String className;

    public LiveClassVisitor(ClassVisitor cv, String source, ClassMetadata classMetadata) {
        super(Opcodes.ASM7, cv);
        this.source = source;
        this.classMetadata = classMetadata;
    }

    @Override
    public void visit(int version, int access,
                      String name, String signature, String superName,
                      String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        className = name;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor originalMV = super.visitMethod(access, name, desc, signature, exceptions);
        MethodVisitor breakpointMethodVisitor = new LiveBreakpointTransformer(
                source, className, name, desc, access, classMetadata, originalMV);
        return new LiveLogTransformer(
                source, className, name, desc, access, classMetadata, breakpointMethodVisitor);
    }
}
