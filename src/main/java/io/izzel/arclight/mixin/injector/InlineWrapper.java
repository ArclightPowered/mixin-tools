package io.izzel.arclight.mixin.injector;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.transformer.MixinTargetContext;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;

public class InlineWrapper {

    private static final String INIT_DESC = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(MixinTargetContext.class), Type.getType(MethodNode.class), Type.getType(AnnotationNode.class));

    public static Constructor<?> wrap(Class<? extends InjectionInfo> cl) throws Exception {
        Class<?> wrapperClass = MethodHandles.privateLookupIn(cl, MethodHandles.lookup()).defineClass(makeWrapper(cl));
        return wrapperClass.getDeclaredConstructor(MixinTargetContext.class, MethodNode.class, AnnotationNode.class);
    }

    private static byte[] makeWrapper(Class<? extends InjectionInfo> cl) {
        var node = new ClassNode();
        node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            Type.getInternalName(cl) + "$InlineWrapper", null, Type.getInternalName(cl), null
        );
        {
            var init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", INIT_DESC, null, null);
            init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int i = 1;
            for (var argumentType : Type.getArgumentTypes(INIT_DESC)) {
                init.instructions.add(new VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), i));
                i += argumentType.getSize();
            }
            init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, Type.getInternalName(cl), "<init>", INIT_DESC, false));
            init.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(init);
        }
        {
            var inject = new MethodNode(Opcodes.ACC_PUBLIC, "inject", "()V", null, null);
            inject.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            inject.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, Type.getInternalName(cl), "inject", "()V", false));
            inject.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            inject.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(InlineCallback.class), "inlineCallback", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(InjectionInfo.class)), false));
            inject.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(inject);
        }
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return cw.toByteArray();
    }
}
