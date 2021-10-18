package spp.probe.services.instrument;

import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Label;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.MethodVisitor;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes;
import org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Type;
import spp.probe.services.common.model.ClassField;
import spp.probe.services.common.model.ClassMetadata;
import spp.probe.services.common.model.LocalVariable;
import spp.probe.services.common.model.Location;
import spp.probe.services.common.transform.LiveTransformer;
import spp.probe.services.instrument.model.LiveInstrument;
import spp.probe.services.instrument.model.LiveLog;

import static org.apache.skywalking.apm.dependencies.net.bytebuddy.jar.asm.Opcodes.*;

public class LiveInstrumentTransformer extends MethodVisitor {

    private static final String THROWABLE_INTERNAL_NAME = Type.getInternalName(Throwable.class);
    private static final String REMOTE_CLASS_LOCATION = "spp/probe/control/LiveInstrumentRemote";
    private static final String REMOTE_CHECK_DESC = "(Ljava/lang/String;)Z";
    private static final String REMOTE_SAVE_VAR_DESC = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V";
    private static final String PUT_LOG_DESC = "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V";
    private final String source;
    private final String className;
    private final String methodUniqueName;
    private final int access;
    private final ClassMetadata classMetadata;

    public LiveInstrumentTransformer(String source, String className, String methodName, String desc, int access,
                                     ClassMetadata classMetadata, MethodVisitor mv) {
        super(ASM7, mv);
        this.source = source;
        this.className = className;
        this.methodUniqueName = methodName + desc;
        this.access = access;
        this.classMetadata = classMetadata;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        mv.visitLineNumber(line, start);
        for (LiveInstrument instrument : LiveInstrumentService.getInstruments(new Location(source, line))) {
            Label instrumentLabel = new Label();
            isInstrumentEnabled(instrument.getId(), instrumentLabel);

            if (instrument instanceof LiveLog) {
                LiveLog log = (LiveLog) instrument;
                if (log.getLogArguments().length > 0 || log.getExpression() != null) {
                    captureSnapshot(log.getId(), line);
                }
                isHit(log.getId(), instrumentLabel);
                processForLog(log);
            } else {
                captureSnapshot(instrument.getId(), line);
                isHit(instrument.getId(), instrumentLabel);
                processForBreakpoint(instrument.getId(), source, line);
            }
            mv.visitLabel(instrumentLabel);
        }
    }

    private void isInstrumentEnabled(String instrumentId, Label instrumentLabel) {
        mv.visitLdcInsn(instrumentId);
        mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION, "isInstrumentEnabled",
                REMOTE_CHECK_DESC, false);
        mv.visitJumpInsn(IFEQ, instrumentLabel);
    }

    private void captureSnapshot(String breakpointId, int line) {
        addLocals(breakpointId, line);
        addStaticFields(breakpointId);
        addFields(breakpointId);
    }

    private void isHit(String breakpointId, Label breakpointLabel) {
        mv.visitLdcInsn(breakpointId);
        mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION, "isHit",
                REMOTE_CHECK_DESC, false);
        mv.visitJumpInsn(IFEQ, breakpointLabel);
    }

    private void processForBreakpoint(String breakpointId, String source, int line) {
        putBreakpoint(breakpointId, source, line);
        mv.visitLabel(new Label());
    }

    private void processForLog(LiveLog log) {
        putLog(log);
        mv.visitLabel(new Label());
    }

    private void addLocals(String breakpointId, int line) {
        for (LocalVariable var : classMetadata.getVariables().get(methodUniqueName)) {
            if (line >= var.getStart() && line < var.getEnd()) {
                mv.visitLdcInsn(breakpointId);
                mv.visitLdcInsn(var.getName());
                mv.visitVarInsn(Type.getType(var.getDesc()).getOpcode(ILOAD), var.getIndex());

                LiveTransformer.boxIfNecessary(mv, var.getDesc());
                mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION,
                        "putLocalVariable", REMOTE_SAVE_VAR_DESC, false);
            }
        }
    }

    private void addStaticFields(String breakpointId) {
        for (ClassField field : classMetadata.getStaticFields()) {
            mv.visitLdcInsn(breakpointId);
            mv.visitLdcInsn(field.getName());
            mv.visitFieldInsn(GETSTATIC, className, field.getName(), field.getDesc());

            LiveTransformer.boxIfNecessary(mv, field.getDesc());
            mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION,
                    "putStaticField", REMOTE_SAVE_VAR_DESC, false);
        }
    }

    private void addFields(String instrumentId) {
        if ((access & Opcodes.ACC_STATIC) == 0) {
            for (ClassField field : classMetadata.getFields()) {
                mv.visitLdcInsn(instrumentId);
                mv.visitLdcInsn(field.getName());
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, className, field.getName(), field.getDesc());

                LiveTransformer.boxIfNecessary(mv, field.getDesc());
                mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION,
                        "putField", REMOTE_SAVE_VAR_DESC, false);
            }
        }
    }

    private void putBreakpoint(String instrumentId, String source, int line) {
        mv.visitLdcInsn(instrumentId);
        mv.visitLdcInsn(source);
        mv.visitLdcInsn(line);
        mv.visitTypeInsn(NEW, THROWABLE_INTERNAL_NAME);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, THROWABLE_INTERNAL_NAME,
                "<init>",
                "()V",
                false);

        mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION,
                "putBreakpoint",
                "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Throwable;)V", false);
    }

    private void putLog(LiveLog log) {
        mv.visitLdcInsn(log.getId());
        mv.visitLdcInsn(log.getLogFormat());

        mv.visitIntInsn(Opcodes.BIPUSH, log.getLogArguments().length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
        for (int i = 0; i < log.getLogArguments().length; i++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, i);
            mv.visitLdcInsn(log.getLogArguments()[i]);
            mv.visitInsn(Opcodes.AASTORE);
        }

        mv.visitMethodInsn(INVOKESTATIC, REMOTE_CLASS_LOCATION, "putLog", PUT_LOG_DESC, false);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        mv.visitMaxs(Math.max(maxStack, 4), maxLocals);
    }
}
