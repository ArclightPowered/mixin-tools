package io.izzel.arclight.mixin.injector;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;

import java.util.ArrayList;
import java.util.List;

public class EnhancedAnalyzerAdapter extends AnalyzerAdapter {

    public EnhancedAnalyzerAdapter(String owner, int access, String name, String descriptor, MethodVisitor methodVisitor) {
        super(Opcodes.ASM9, owner, access, name, descriptor, methodVisitor);
        this.recordLocalsStack();
    }

    protected EnhancedAnalyzerAdapter(int api, String owner, int access, String name, String descriptor, MethodVisitor methodVisitor) {
        super(api, owner, access, name, descriptor, methodVisitor);
        this.recordLocalsStack();
    }

    private List<Object> lastLocal, lastStack;

    @Override
    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        switch (type) {
            case Opcodes.F_NEW -> super.visitFrame(type, numLocal, local, numStack, stack);
            case Opcodes.F_FULL -> super.visitFrame(Opcodes.F_NEW, numLocal, local, numStack, stack);
            case Opcodes.F_APPEND -> {
                for (int i = 0; i < numLocal; i++) {
                    lastLocal.add(local[i]);
                }
                super.visitFrame(Opcodes.F_NEW, lastLocal.size(), lastLocal.toArray(), 0, null);
            }
            case Opcodes.F_CHOP -> {
                for (int i = 0; i < numLocal; i++) {
                    lastLocal.remove(lastLocal.size() - 1);
                }
                super.visitFrame(Opcodes.F_NEW, lastLocal.size(), lastLocal.toArray(), 0, null);
            }
            case Opcodes.F_SAME -> super.visitFrame(Opcodes.F_NEW, lastLocal.size(), lastLocal.toArray(), 0, null);
            case Opcodes.F_SAME1 ->
                super.visitFrame(Opcodes.F_NEW, lastLocal.size(), lastLocal.toArray(), numStack, stack);
        }
        this.recordLocalsStack();
    }

    private void recordLocalsStack() {
        this.lastLocal = new ArrayList<>();
        this.lastStack = new ArrayList<>();
        boolean doubleOrLong = false;
        for (Object local : this.locals) {
            if (doubleOrLong) {
                doubleOrLong = false;
                continue;
            }
            if (local == Opcodes.LONG || local == Opcodes.DOUBLE) {
                doubleOrLong = true;
            }
            this.lastLocal.add(local);
        }
        doubleOrLong = false;
        for (Object stack : this.stack) {
            if (doubleOrLong) {
                doubleOrLong = false;
                continue;
            }
            if (stack == Opcodes.LONG || stack == Opcodes.DOUBLE) {
                doubleOrLong = true;
            }
            this.lastStack.add(stack);
        }
    }

    public List<Object> getCurrent(List<Object> frame) {
        var lastStack = new ArrayList<>();
        boolean doubleOrLong = false;
        for (Object stack : frame) {
            if (doubleOrLong) {
                doubleOrLong = false;
                continue;
            }
            if (stack == Opcodes.LONG || stack == Opcodes.DOUBLE) {
                doubleOrLong = true;
            }
            lastStack.add(stack);
        }
        return lastStack;
    }

    public static boolean canFit(Object frameObject, Type type) {
        if (frameObject.equals(Opcodes.TOP)) {
            return false;
        } else if (frameObject.equals(Opcodes.INTEGER)) {
            return type.equals(Type.INT_TYPE) || type.equals(Type.SHORT_TYPE)
                || type.equals(Type.CHAR_TYPE) || type.equals(Type.BYTE_TYPE) || type.equals(Type.BOOLEAN_TYPE);
        } else if (frameObject.equals(Opcodes.FLOAT)) {
            return type.equals(Type.FLOAT_TYPE);
        } else if (frameObject.equals(Opcodes.DOUBLE)) {
            return type.equals(Type.DOUBLE_TYPE);
        } else if (frameObject.equals(Opcodes.LONG)) {
            return type.equals(Type.LONG_TYPE);
        } else if (frameObject.equals(Opcodes.NULL)) {
            return type.getSort() == Type.ARRAY || type.getSort() == Type.OBJECT;
        } else if (frameObject.equals(Opcodes.UNINITIALIZED_THIS)) {
            return false;
        } else if (frameObject instanceof String internalName) {
            return Type.getObjectType(internalName).getSort() == type.getSort(); // hopefully
        } else if (frameObject instanceof Label) {
            return false;
        } else {
            throw new IllegalArgumentException("Unknown frame item " + frameObject);
        }
    }

    public static Object getFrameItem(Type type) {
        if (type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.INT) {
            return Opcodes.INTEGER;
        } else if (type.equals(Type.FLOAT_TYPE)) {
            return Opcodes.FLOAT;
        } else if (type.equals(Type.LONG_TYPE)) {
            return Opcodes.LONG;
        } else if (type.equals(Type.DOUBLE_TYPE)) {
            return Opcodes.DOUBLE;
        } else if (type.getSort() >= Type.ARRAY && type.getSort() <= Type.OBJECT) {
            return type.getInternalName();
        } else {
            throw new IllegalArgumentException("Unknown method argument type " + type);
        }
    }

    public static int getOpcode(int opcode, Object frameObject) {
        if (frameObject.equals(Opcodes.TOP)) {
            throw new IllegalArgumentException("Unexpected TOP on stack");
        } else if (frameObject.equals(Opcodes.INTEGER)) {
            return Type.INT_TYPE.getOpcode(opcode);
        } else if (frameObject.equals(Opcodes.FLOAT)) {
            return Type.FLOAT_TYPE.getOpcode(opcode);
        } else if (frameObject.equals(Opcodes.DOUBLE)) {
            return Type.DOUBLE_TYPE.getOpcode(opcode);
        } else if (frameObject.equals(Opcodes.LONG)) {
            return Type.LONG_TYPE.getOpcode(opcode);
        } else if (frameObject.equals(Opcodes.NULL)) {
            return Type.getType(Object.class).getOpcode(opcode);
        } else if (frameObject.equals(Opcodes.UNINITIALIZED_THIS)) {
            return Type.getType(Object.class).getOpcode(opcode);
        } else if (frameObject instanceof String) {
            return Type.getType(Object.class).getOpcode(opcode);
        } else if (frameObject instanceof Label) {
            return Type.getType(Object.class).getOpcode(opcode);
        } else {
            throw new IllegalArgumentException("Unknown frame item " + frameObject);
        }
    }
}
