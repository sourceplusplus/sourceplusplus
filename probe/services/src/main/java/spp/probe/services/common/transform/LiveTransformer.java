package spp.probe.services.common.transform;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.*;
import spp.probe.services.common.model.ClassMetadata;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;

public class LiveTransformer implements ClassFileTransformer {

    private final String source;

    public LiveTransformer(String source) {
        this.source = source;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!className.replace('/', '.').equals(source)) {
            return null;
        }

        ClassReader classReader = new ClassReader(classfileBuffer);
        ClassMetadata classMetadata = new ClassMetadata();
        classReader.accept(new MetadataCollector(classMetadata), ClassReader.SKIP_FRAMES);
        ClassWriter classWriter = new ClassWriter(computeFlag(classReader));
        ClassVisitor classVisitor = new LiveClassVisitor(classWriter, source, classMetadata);
        classReader.accept(classVisitor, ClassReader.SKIP_FRAMES);
        return classWriter.toByteArray();
    }

    protected int computeFlag(ClassReader classReader) {
        int flag = ClassWriter.COMPUTE_MAXS;
        short version = classReader.readShort(6);
        if (version >= Opcodes.V1_7) {
            flag = ClassWriter.COMPUTE_FRAMES;
        }
        return flag;
    }

    public static void boxIfNecessary(MethodVisitor mv, String desc) {
        switch (Type.getType(desc).getSort()) {
            case Type.BOOLEAN:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                break;
            case Type.BYTE:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                break;
            case Type.CHAR:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                break;
            case Type.DOUBLE:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
            case Type.FLOAT:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                break;
            case Type.INT:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case Type.LONG:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case Type.SHORT:
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                break;
        }
    }
}
