package io.izzel.arclight.mixin.injector;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.InjectionPoint;
import org.spongepowered.asm.mixin.injection.code.Injector;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionNodes;
import org.spongepowered.asm.mixin.injection.struct.Target;
import org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException;
import org.spongepowered.asm.util.Bytecode;
import org.spongepowered.asm.util.Constants;
import org.spongepowered.asm.util.SignaturePrinter;

import java.util.Arrays;

public class Ejector extends Injector {

    static class EjectInvokeData extends InjectorData {

        final MethodInsnNode node;
        final Type returnType;
        final Type[] targetArgs;
        final Type[] handlerArgs;

        EjectInvokeData(Target target, MethodInsnNode node) {
            super(target);
            this.node = node;
            this.returnType = Type.getReturnType(node.desc);
            this.targetArgs = Type.getArgumentTypes(node.desc);
            var handlerArgs = this.targetArgs;
            if (node.getOpcode() != Opcodes.INVOKESTATIC) {
                handlerArgs = new Type[this.targetArgs.length + 1];
                handlerArgs[0] = Type.getObjectType(node.owner);
                System.arraycopy(this.targetArgs, 0, handlerArgs, 1, this.targetArgs.length);
            }
            this.handlerArgs = handlerArgs;
        }

    }

    public Ejector(InjectionInfo info) {
        super(info, "@Eject");
    }

    private String callbackInfoClass;
    private int callbackInfoVar;

    @Override
    protected void inject(Target target, InjectionNodes.InjectionNode node) {
        if (node.isReplaced()) {
            throw new UnsupportedOperationException("Indirect target failure for " + this.info);
        }
        this.checkTargetForNode(target, node, InjectionPoint.RestrictTargetLevel.CONSTRUCTORS_AFTER_DELEGATE);

        if (node.getCurrentTarget() instanceof MethodInsnNode) {
            this.checkTargetForNode(target, node, InjectionPoint.RestrictTargetLevel.ALLOW_ALL);
            this.injectAtInvoke(target, node);
            return;
        }
        throw new InvalidInjectionException(this.info, String.format("%s annotation on is targeting an invalid insn in %s in %s",
            this.annotationType, target, this));
    }

    private void injectAtInvoke(Target target, InjectionNodes.InjectionNode node) {
        EjectInvokeData data = new EjectInvokeData(target, ((MethodInsnNode) node.getCurrentTarget()));
        this.validateIndirectParams(data, data.returnType, data.handlerArgs);
        InsnList insnList = new InsnList();

        Target.Extension extraLocals = target.extendLocals();
        Target.Extension extraStack = target.extendStack();

        instanceCallbackInfo(insnList, target);
        AbstractInsnNode insnNode = invokeCallback(target, insnList, data, extraLocals, extraStack);
        injectCancellationCode(insnList, target);
        target.replaceNode(data.node, insnNode, insnList);

        extraLocals.apply();
        extraStack.apply();
    }

    protected void injectCancellationCode(InsnList callback, Target target) {
        callback.add(new VarInsnNode(Opcodes.ALOAD, this.callbackInfoVar));
        callback.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, this.callbackInfoClass, "isCancelled", "()Z", false));
        LabelNode notCancelled = new LabelNode();
        callback.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
        this.injectReturnCode(callback, target);
        callback.add(notCancelled);
    }

    protected void injectReturnCode(InsnList callback, Target target) {
        if (target.returnType.equals(Type.VOID_TYPE)) {
            callback.add(new InsnNode(Opcodes.RETURN));
        } else {
            callback.add(new VarInsnNode(Opcodes.ALOAD, this.callbackInfoVar));
            String accessor = getReturnAccessor(target.returnType);
            String descriptor = getReturnDescriptor(target.returnType);
            callback.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, this.callbackInfoClass, accessor, descriptor, false));
            if (target.returnType.getSort() >= Type.ARRAY) {
                callback.add(new TypeInsnNode(Opcodes.CHECKCAST, target.returnType.getInternalName()));
            }
            callback.add(new InsnNode(target.returnType.getOpcode(Opcodes.IRETURN)));
        }
    }

    protected AbstractInsnNode invokeCallback(Target target, InsnList insnList, EjectInvokeData data, Target.Extension extraLocals, Target.Extension extraStack) {
        extraLocals.add(data.handlerArgs).add(2);
        extraStack.add(2);
        int[] argMap = this.storeArgs(target, data.handlerArgs, insnList, 0);
        var newArgsMap = new int[argMap.length + data.captureTargetArgs + 1];
        System.arraycopy(argMap, 0, newArgsMap, 0, argMap.length);
        newArgsMap[argMap.length] = this.callbackInfoVar;
        if (data.captureTargetArgs > 0) {
            int argSize = Bytecode.getArgsSize(target.arguments, 0, data.captureTargetArgs);
            extraLocals.add(argSize);
            extraStack.add(argSize);
            System.arraycopy(target.getArgIndices(), 0, newArgsMap, argMap.length + 1, data.captureTargetArgs);
        }
        AbstractInsnNode champion = this.invokeHandlerWithArgs(this.methodArgs, insnList, newArgsMap);
        if (data.coerceReturnType && data.returnType.getSort() >= Type.ARRAY) {
            insnList.add(new TypeInsnNode(Opcodes.CHECKCAST, data.returnType.getInternalName()));
        }
        return champion;
    }

    protected void instanceCallbackInfo(InsnList callback, Target target) {
        callback.add(new TypeInsnNode(Opcodes.NEW, this.callbackInfoClass));
        callback.add(new InsnNode(Opcodes.DUP));
        callback.add(new LdcInsnNode(target.method.name));
        callback.add(new InsnNode(Opcodes.ICONST_1));
        callback.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, this.callbackInfoClass, Constants.CTOR, String.format("(%sZ)V", Constants.STRING_DESC), false));

        this.callbackInfoVar = target.allocateLocal();
        target.addLocalVariable(this.callbackInfoVar, "callbackInfo" + this.callbackInfoVar, "L" + this.callbackInfoClass + ";");
        callback.add(new VarInsnNode(Opcodes.ASTORE, this.callbackInfoVar));
    }

    protected final void validateIndirectParams(EjectInvokeData injector, Type returnType, Type... args) {
        String description = String.format("%s %s method %s from %s", this.annotationType, injector, this, this.info.toString());
        int argIndex = 0;
        try {
            injector.coerceReturnType = this.checkCoerce(-1, returnType, description, injector.allowCoerceArgs);

            for (Type arg : args) {
                if (arg != null) {
                    this.checkCoerce(argIndex, arg, description, injector.allowCoerceArgs);
                    argIndex++;
                }
            }

            if (argIndex >= this.methodArgs.length) {
                throw new InvalidInjectionException(this.info, "Not enough arguments, expect CallbackInfo or CallbackInfoReturnable, found "
                    + this.methodNode.desc);
            }

            if (this.callbackInfoClass == null) {
                this.callbackInfoClass = injector.target.getCallbackInfoClass();
            }
            this.checkCoerce(argIndex, Type.getObjectType(this.callbackInfoClass), description, false);
            argIndex++;

            for (int targetArg = 0; targetArg < injector.target.arguments.length && argIndex < this.methodArgs.length; targetArg++, argIndex++) {
                this.checkCoerce(argIndex, injector.target.arguments[targetArg], description, true);
                injector.captureTargetArgs++;
            }
        } catch (InvalidInjectionException ex) {
            var expectedArgs = args;
            if (this.methodArgs.length > args.length) {
                expectedArgs = new Type[args.length + injector.target.arguments.length];
                System.arraycopy(args, 0, expectedArgs, 0, args.length);
                System.arraycopy(injector.target.arguments, 0, expectedArgs, args.length, injector.target.arguments.length);
            }
            String expected = Bytecode.generateDescriptor(returnType, expectedArgs);
            throw new InvalidInjectionException(this.info, String.format("%s. Handler signature: %s Expected signature: %s", ex.getMessage(),
                this.methodNode.desc, expected));
        }

        if (argIndex < this.methodArgs.length) {
            Type[] extraArgs = Arrays.copyOfRange(this.methodArgs, argIndex, this.methodArgs.length);
            throw new InvalidInjectionException(this.info, String.format(
                "%s has an invalid signature. Found %d unexpected additional method arguments: %s",
                description, this.methodArgs.length - argIndex, new SignaturePrinter(extraArgs).getFormattedArgs()));
        }
    }

    static String getReturnAccessor(Type returnType) {
        if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
            return "getReturnValue";
        }

        return String.format("getReturnValue%s", returnType.getDescriptor());
    }

    static String getReturnDescriptor(Type returnType) {
        if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
            return String.format("()%s", Constants.OBJECT_DESC);
        }

        return String.format("()%s", returnType.getDescriptor());
    }
}
