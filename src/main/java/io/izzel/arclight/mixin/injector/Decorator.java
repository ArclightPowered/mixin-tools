package io.izzel.arclight.mixin.injector;

import io.izzel.arclight.mixin.DecorationOps;
import io.izzel.arclight.mixin.Local;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.InjectionPoint;
import org.spongepowered.asm.mixin.injection.code.Injector;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionNodes.InjectionNode;
import org.spongepowered.asm.mixin.injection.struct.Target;
import org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException;
import org.spongepowered.asm.util.Locals;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public class Decorator extends Injector {

    private static final Type DECORATION_TYPE = Type.getType(DecorationOps.class);
    private static final Type MH_TYPE = Type.getType(MethodHandle.class);
    private static final Type DECORATION_METHOD = Type.getMethodType(MH_TYPE);
    private static final String DECORATION_CALLSITE = "callsite";
    private static final String DECORATION_CANCEL = "cancel";
    private static final String DECORATION_BLACKHOLE = "blackhole";
    private static final String MH_INVOKE = "invoke";
    private static final String LOCAL_DESC = Type.getDescriptor(Local.class);

    public Decorator(InjectionInfo info) {
        super(info, "@Decorate");
    }

    enum DecorationTarget {
        INVOKE, FIELD, NEW
    }

    protected static class DecorationData extends InjectorData {

        final AbstractInsnNode node, nodeEnd;
        final DecorationTarget decorationTarget;
        final Type returnType;
        final Type[] targetArgs;
        final Type[] handlerArgs;

        final AbstractInsnNode callsiteDecl, callsiteInvoke;
        final Map<AbstractInsnNode, MethodInsnNode> cancels, blackholes; // decl -> invoke

        final boolean requireFrame;

        final LabelNode begin = new LabelNode(), end = new LabelNode();
        final LocalVariableNode[] locals;

        boolean hasEnd = false;
        int handlersLocalStart;
        List<Object> targetLocals;
        int[] lvtMap;

        DecorationData(Target target, AbstractInsnNode node, AbstractInsnNode callsiteDecl,
                       AbstractInsnNode callsiteInvoke, Map<AbstractInsnNode, MethodInsnNode> cancels,
                       Map<AbstractInsnNode, MethodInsnNode> blackholes, boolean requireFrame, LocalVariableNode[] locals) {
            super(target);
            this.node = node;
            this.callsiteDecl = callsiteDecl;
            this.callsiteInvoke = callsiteInvoke;
            this.cancels = cancels;
            this.blackholes = blackholes;
            this.requireFrame = requireFrame;
            this.locals = locals;
            if (node instanceof MethodInsnNode mn) {
                this.returnType = Type.getReturnType(mn.desc);
                this.targetArgs = Type.getArgumentTypes(mn.desc);
                var handlerArgs = this.targetArgs;
                if (node.getOpcode() != Opcodes.INVOKESTATIC) {
                    handlerArgs = new Type[this.targetArgs.length + 1];
                    handlerArgs[0] = Type.getObjectType(mn.owner);
                    System.arraycopy(this.targetArgs, 0, handlerArgs, 1, this.targetArgs.length);
                }
                this.handlerArgs = handlerArgs;
                this.nodeEnd = node;
                this.decorationTarget = DecorationTarget.INVOKE;
            } else if (node instanceof FieldInsnNode fn) {
                switch (node.getOpcode()) {
                    case Opcodes.GETFIELD -> {
                        this.returnType = Type.getType(fn.desc);
                        this.targetArgs = this.handlerArgs = new Type[]{Type.getObjectType(fn.owner)};
                    }
                    case Opcodes.GETSTATIC -> {
                        this.returnType = Type.getType(fn.desc);
                        this.targetArgs = this.handlerArgs = new Type[]{};
                    }
                    case Opcodes.PUTFIELD -> {
                        this.returnType = Type.VOID_TYPE;
                        this.targetArgs = this.handlerArgs = new Type[]{Type.getObjectType(fn.owner),
                                Type.getType(fn.desc)};
                    }
                    case Opcodes.PUTSTATIC -> {
                        this.returnType = Type.VOID_TYPE;
                        this.targetArgs = this.handlerArgs = new Type[]{Type.getType(fn.desc)};
                    }
                    default -> throw new IllegalArgumentException("Unknown opcode " + node.getOpcode());
                }
                this.nodeEnd = node;
                this.decorationTarget = DecorationTarget.FIELD;
            } else if (node instanceof TypeInsnNode tn && tn.getOpcode() == Opcodes.NEW) {
                this.returnType = Type.getObjectType(tn.desc);
                var initNode = target.findInitNodeFor(tn);
                if (initNode == null) {
                    throw new IllegalArgumentException(
                            "No <init> call for NEW at bci " + target.method.instructions.indexOf(tn));
                }
                this.targetArgs = this.handlerArgs = Type.getArgumentTypes(initNode.desc);
                this.nodeEnd = initNode;
                this.decorationTarget = DecorationTarget.NEW;
            } else {
                throw new UnsupportedOperationException("Invalid target type " + node);
            }
        }
    }

    private LocalVariableNode[] locals;

    @Override
    protected void preInject(Target target, InjectionNode node) {
        this.locals = Locals.getLocalsAt(target.classNode, target.method, node.getCurrentTarget(), Locals.Settings.DEFAULT);
    }

    @Override
    protected void inject(Target target, InjectionNode node) {
        if (node.isReplaced()) {
            throw new UnsupportedOperationException("Indirect target failure for " + this.info);
        }
        this.checkTargetForNode(target, node, InjectionPoint.RestrictTargetLevel.ALLOW_ALL);
        var decorationData = createDecorationData(target, node);
        this.injectAt(target, node, decorationData);
    }

    protected DecorationData createDecorationData(Target target, InjectionNode node) {
        AbstractInsnNode callsiteDecl = null, callsiteInvoke = null;
        Map<AbstractInsnNode, MethodInsnNode> cancels = new HashMap<>(), blackholes = new HashMap<>();
        enum LastDecl {
            CALLSITE, CANCEL, BLACKHOLE
        }
        LastDecl lastDecl = null;
        AbstractInsnNode lastDeclInsn = null;
        boolean requireFrame = false;
        for (var insn : this.methodNode.instructions) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode mn) {
                if (mn.owner.equals(DECORATION_TYPE.getInternalName())
                        && mn.desc.equals(DECORATION_METHOD.getDescriptor())) {
                    if (lastDecl != null) {
                        throw new InvalidInjectionException(this.info, "Nested DecorationOps at bci "
                                + this.methodNode.instructions.indexOf(lastDeclInsn) + ", "
                                + this.methodNode.instructions.indexOf(mn));
                    }
                    lastDeclInsn = mn;
                    switch (mn.name) {
                        case DECORATION_CALLSITE -> {
                            if (callsiteDecl != null) {
                                throw new InvalidInjectionException(this.info,
                                        "Multiple callsite found in @Decorate: bci "
                                                + this.methodNode.instructions.indexOf(callsiteDecl) + ", "
                                                + this.methodNode.instructions.indexOf(mn));
                            }
                            callsiteDecl = mn;
                            lastDecl = LastDecl.CALLSITE;
                        }
                        case DECORATION_CANCEL -> lastDecl = LastDecl.CANCEL;
                        case DECORATION_BLACKHOLE -> lastDecl = LastDecl.BLACKHOLE;
                    }
                }
            } else if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode mn) {
                if (mn.owner.equals(MH_TYPE.getInternalName()) && mn.name.equals(MH_INVOKE)) {
                    if (lastDecl != null) {
                        switch (lastDecl) {
                            case CALLSITE -> callsiteInvoke = mn;
                            case CANCEL -> cancels.put(lastDeclInsn, mn);
                            case BLACKHOLE -> blackholes.put(lastDeclInsn, mn);
                        }
                    }
                    lastDecl = null;
                    lastDeclInsn = null;
                }
            } else if (insn instanceof FrameNode) {
                requireFrame = true;
            } else if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN
                    && insn.getNext() != null) {
                requireFrame = true;
            }
        }
        if (callsiteDecl == null || callsiteInvoke == null) {
            throw new InvalidInjectionException(this.info, "No callsite found in @Decorate");
        }
        if (lastDeclInsn != null) {
            throw new InvalidInjectionException(this.info,
                    "Open DecorationOps in @Decorate: " + lastDecl + " at bci "
                            + this.methodNode.instructions.indexOf(callsiteDecl));
        }
        for (var invoke : cancels.values()) {
            var methodType = Type.getMethodType(invoke.desc);
            if (!methodType.getReturnType().equals(Type.VOID_TYPE)) {
                throw new InvalidInjectionException(this.info,
                        "Invalid DecorationOps.cancel return type: bci "
                                + this.methodNode.instructions.indexOf(invoke));
            }
            var argumentTypes = methodType.getArgumentTypes();
            if (argumentTypes.length != 1 || !argumentTypes[0].equals(target.returnType)) {
                throw new InvalidInjectionException(this.info,
                        "Invalid DecorationOps.cancel argument types: bci "
                                + this.methodNode.instructions.indexOf(invoke));
            }
        }
        for (var invoke : blackholes.values()) {
            var methodType = Type.getMethodType(invoke.desc);
            if (!methodType.getReturnType().equals(Type.VOID_TYPE)) {
                throw new InvalidInjectionException(this.info,
                        "Invalid DecorationOps.blackhole return type: bci "
                                + this.methodNode.instructions.indexOf(invoke));
            }
        }
        var data = new DecorationData(target, node.getCurrentTarget(), callsiteDecl, callsiteInvoke, cancels,
                blackholes, requireFrame, this.locals);
        if (!data.returnType.equals(Type.getReturnType(this.methodNode.desc))) {
            throw new InvalidInjectionException(this.info, "Return type mismatch: expect " + data.returnType
                    + ", found " + Type.getReturnType(this.methodNode.desc));
        }
        var argTypes = Type.getArgumentTypes(this.methodNode.desc);
        var targetArgs = data.targetArgs;
        for (var i = 0; i < targetArgs.length; i++) {
            var targetArg = targetArgs[i];
            if (!argTypes[i].equals(targetArg)) {
                throw new InvalidInjectionException(this.info,
                        "Callback argument type mismatch at " + i + ": expect " + targetArg + ", found " + argTypes[i]);
            }
        }
        return data;
    }

    protected void injectAt(Target target, InjectionNode node, DecorationData decorationData) {
        this.guardInline(target, node, decorationData, decorationData.handlerArgs);
        decorationData.lvtMap = this.prepareLvtMapping(target, decorationData);
        this.performInline(target, node, decorationData);
    }

    private void guardInline(Target target, InjectionNode node, DecorationData decorationData,
                             Type[] handlerTypes) {
        var adapter = new EnhancedAnalyzerAdapter(target.classNode.name, target.method.access, target.method.name,
                target.method.desc, null);
        for (int i = target.insns.indexOf(node.getCurrentTarget()), j = 0; j < i; j++) {
            target.insns.get(j).accept(adapter);
        }
        var currentLocal = adapter.getCurrent(adapter.locals);
        var currentStack = adapter.getCurrent(adapter.stack);
        decorationData.targetLocals = currentLocal;
        if (currentStack.size() < handlerTypes.length) {
            throw new InvalidInjectionException(this.info, "Stack size is not large enough");
        }
        for (int i = 0; i < handlerTypes.length; i++) {
            var handlerType = handlerTypes[i];
            if (!EnhancedAnalyzerAdapter.canFit(currentStack.get(currentStack.size() - handlerTypes.length + i),
                    handlerType)) {
                throw new InvalidInjectionException(this.info, "Stack element not match argument type: frame "
                        + currentStack.get(i) + ", argument " + handlerType);
            }
        }

        var beforeDecorate = new InsnList();
        var afterDecorate = new InsnList();

        // 1. store handler args
        int unusedStackElmSize = currentStack.subList(handlerTypes.length, currentStack.size()).stream()
                .mapToInt(it -> (it == Opcodes.LONG || it == Opcodes.DOUBLE) ? 2 : 1).sum();
        int handlerStartIndex = adapter.locals.size() + unusedStackElmSize;
        decorationData.handlersLocalStart = handlerStartIndex;
        this.storeArgs(target, handlerTypes, beforeDecorate, handlerStartIndex);
        // 2. store unused stack elements, likely 0
        for (int i = 0, lvIndex = adapter.locals.size(); i < currentStack.size() - handlerTypes.length; i++) {
            beforeDecorate.add(
                    new VarInsnNode(EnhancedAnalyzerAdapter.getOpcode(Opcodes.ISTORE, currentStack.get(i)), lvIndex));
            afterDecorate.insert(
                    new VarInsnNode(EnhancedAnalyzerAdapter.getOpcode(Opcodes.ILOAD, currentStack.get(i)), lvIndex));
            lvIndex += (currentStack.get(i) == Opcodes.LONG || currentStack.get(i) == Opcodes.DOUBLE) ? 2 : 1;
        }
        if (!decorationData.requireFrame && afterDecorate.size() == 0) {
            target.insertBefore(node.getCurrentTarget(), beforeDecorate);
            return;
        }
        // 3. insert additional frame node at begin
        var callbackArgs = Type.getArgumentTypes(this.methodNode.desc);
        var callbackLocals = Arrays.stream(callbackArgs).map(EnhancedAnalyzerAdapter::getFrameItem).toArray();
        var mergedLocals = new Object[currentLocal.size() + currentStack.size() + callbackArgs.length];
        System.arraycopy(currentLocal.toArray(), 0, mergedLocals, 0, currentLocal.size());
        System.arraycopy(currentStack.toArray(), 0, mergedLocals, currentLocal.size(), currentStack.size());
        System.arraycopy(callbackLocals, 0, mergedLocals, currentLocal.size() + currentStack.size(),
                callbackLocals.length);
        beforeDecorate.add(decorationData.begin);
        beforeDecorate.add(new FrameNode(Opcodes.F_FULL, mergedLocals.length, mergedLocals, 0, null));
        // 4. rebuild stack after inline invoke if necessary
        //
        // write the return value of callback at handlerStartIndex,
        // load the previous stack stored at step 2,
        // and load the return value
        for (var iterator = target.insns.iterator(target.insns.indexOf(node.getCurrentTarget())); iterator.hasNext(); ) {
            var insn = iterator.next();
            insn.accept(adapter);
            if (insn == decorationData.nodeEnd) {
                break;
            }
        }
        node.getCurrentTarget().accept(adapter);
        List<Object> afterLocal = adapter.getCurrent(adapter.locals),
                afterStack = adapter.getCurrent(adapter.stack);
        if (afterDecorate.size() > 0) {
            var callbackHasReturn = Type.getReturnType(this.methodNode.desc).getSize() > 0;
            var afterWithUnused = new Object[afterLocal.size() + currentLocal.size() - handlerTypes.length
                    + (callbackHasReturn ? 1 : 0)];
            System.arraycopy(afterLocal.toArray(), 0, afterWithUnused, 0, afterLocal.size());
            System.arraycopy(currentStack.toArray(), 0, afterWithUnused, afterLocal.size(),
                    currentLocal.size() - handlerTypes.length);
            if (callbackHasReturn) {
                afterWithUnused[afterWithUnused.length - 1] = EnhancedAnalyzerAdapter
                        .getFrameItem(Type.getReturnType(this.methodNode.desc));
                var storeInsn = new InsnList();
                this.storeArgs(target, new Type[]{Type.getReturnType(this.methodNode.desc)}, storeInsn,
                        handlerStartIndex);
                afterDecorate.insert(storeInsn);
            }
            afterDecorate.insert(new FrameNode(Opcodes.F_FULL, afterWithUnused.length, afterWithUnused,
                    callbackHasReturn ? 1 : 0,
                    callbackHasReturn ? new Object[]{afterWithUnused[afterWithUnused.length - 1]} : null));
            // rebuild stack end
            if (callbackHasReturn) {
                afterDecorate.add(new VarInsnNode(Type.getReturnType(this.methodNode.desc).getOpcode(Opcodes.ILOAD),
                        handlerStartIndex));
            }
        }
        // 5. insert additional frame node at end
        afterDecorate.insert(decorationData.end);
        decorationData.hasEnd = true;
        afterDecorate.add(new FrameNode(Opcodes.F_FULL, afterLocal.size(), afterLocal.toArray(),
                afterStack.size(), afterStack.toArray()));
        // 6. rewrite next frame node if it's not a full frame
        for (var iterator = target.insns
                .iterator(target.insns.indexOf(decorationData.nodeEnd) + 1); iterator.hasNext(); ) {
            var insn = iterator.next();
            insn.accept(adapter);
            if (insn instanceof FrameNode fn) {
                if (fn.type != Opcodes.F_FULL) {
                    var locals = adapter.getCurrent(adapter.locals);
                    var stack = adapter.getCurrent(adapter.stack);
                    iterator.set(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(),
                            stack.size(), stack.toArray()));
                }
                break;
            }
        }
        target.insns.insertBefore(node.getCurrentTarget(), beforeDecorate);
        target.insns.insert(decorationData.nodeEnd, afterDecorate);
    }

    private int[] prepareLvtMapping(Target target, DecorationData decorationData) {
        var lvtMap = new int[this.methodNode.maxLocals];
        Arrays.fill(lvtMap, -1);
        var lvIndex = 0;
        var offset = 0;
        if (!Modifier.isStatic(this.methodNode.access)) {
            lvtMap[lvIndex] = offset;
            lvIndex++;
            offset++;
        }
        var targetArgsStart = decorationData.handlerArgs.length;
        for (int i = 0; i < targetArgsStart; i++) {
            lvtMap[lvIndex] = lvIndex - offset + decorationData.handlersLocalStart;
            lvIndex += this.methodArgs[i].getSize();
        }
        var localsStart = targetArgsStart + target.arguments.length;
        for (int i = targetArgsStart; i < Math.min(this.methodArgs.length, localsStart); i++) {
            lvtMap[lvIndex] = offset;
            lvIndex += this.methodArgs[i].getSize();
            offset += this.methodArgs[i].getSize();
        }
        for (int i = localsStart; i < this.methodArgs.length; i++) {
            lvtMap[lvIndex] = findLv(i);
            lvIndex += this.methodArgs[i].getSize();
            offset += this.methodArgs[i].getSize();
        }
        for (int i = lvIndex; i < this.methodNode.maxLocals; i++) {
            lvtMap[lvIndex] = lvIndex - offset + decorationData.handlersLocalStart;
            lvIndex++;
        }
        return lvtMap;
    }

    private int findLv(int i) {
        var type = this.methodArgs[i];
        AnnotationNode localNode;
        if (this.methodNode.invisibleAnnotableParameterCount > i &&
                (localNode = this.methodNode.invisibleParameterAnnotations[i].stream()
                        .filter(it -> it.desc.equals(LOCAL_DESC)).findAny().orElse(null)) != null) {
            var index = (Integer) localNode.values.get(1);
            var lvns = Arrays.stream(this.locals).filter(it -> Type.getType(it.desc).equals(type)).toList();
            if (index < 0) {
                index = lvns.size() + index;
            }
            if (index < lvns.size()) {
                return lvns.get(index).index;
            } else {
                throw new InvalidInjectionException(this.info, "Cannot find local at " + i + " with ordinal " + localNode.values.get(1) + "\n"
                        + "Available locals:\n"
                        + lvns.stream().map(it -> "Index: " + it.index + " Type " + it.desc + " Name " + it.name).collect(Collectors.joining("\n")));
            }
        } else {
            throw new InvalidInjectionException(this.info, "@Local not exist at local " + i + ": " + type);
        }
    }

    private void performInline(Target target, InjectionNode node, DecorationData decorationData) {
        var collector = new CollectingVisitor(Opcodes.ASM9, target, decorationData);
        switch (decorationData.decorationTarget) {
            case INVOKE, FIELD -> {
                for (var lvn : this.methodNode.localVariables) {
                    lvn.accept(collector);
                }
                for (var insn : this.methodNode.instructions) {
                    if (insn == decorationData.callsiteDecl) {
                        continue;
                    }
                    if (insn == decorationData.callsiteInvoke) {
                        collector.step();
                    } else {
                        collector.next(insn);
                    }
                }
                target.insns.insertBefore(node.getCurrentTarget(), collector.blocks.get(0).instructions);
                target.insns.insert(decorationData.nodeEnd, collector.blocks.get(1).instructions);
            }
            case NEW -> {
                for (var lvn : this.methodNode.localVariables) {
                    lvn.accept(collector);
                }
                for (var insn : this.methodNode.instructions) {
                    if (insn == decorationData.callsiteDecl) {
                        collector.step();
                    } else if (insn == decorationData.callsiteInvoke) {
                        collector.step();
                    } else {
                        collector.next(insn);
                    }
                }
                var initInsns = new InsnList();
                var startNode = decorationData.node.getNext();
                if (startNode.getOpcode() == Opcodes.DUP) {
                    startNode = startNode.getNext();
                }
                for (var iterator = target.insns.iterator(target.insns.indexOf(startNode)); iterator.hasNext(); ) {
                    var insn = iterator.next();
                    if (insn == decorationData.nodeEnd) {
                        break;
                    }
                    initInsns.add(insn);
                    iterator.remove();
                }
                // @formatter:off
                // original:                                      | callback:                                   
                //   a: code block before NEW                     |   d: code block before callsite decl         
                //   NEW                                          |   callback decl                            
                //   DUP                                          |   e: code block between decl and invoke    
                //   b: code block between NEW and INVOKESPECIAL  |   callback invoke                            
                //   INVOESPECIAL <init>                          |   f: code block after                        
                //   c: code block after                          |                                              
                //                                              
                // inlined:
                //   a
                //   d
                //   b     // b is reordered before NEW
                //   NEW
                //   DUP
                //   e
                //   INVOKESPECIAL
                //   f
                //   c     
                // @formatter:on                                                                                         
                target.insns.insertBefore(decorationData.node, initInsns); // reorder b
                target.insns.insertBefore(decorationData.node, collector.blocks.get(0).instructions); // d
                target.insns.insertBefore(decorationData.nodeEnd, collector.blocks.get(1).instructions); // e
                target.insns.insert(decorationData.nodeEnd, collector.blocks.get(2).instructions); // f
            }
        }
        target.method.tryCatchBlocks.addAll(0, this.methodNode.tryCatchBlocks);
        target.method.localVariables.addAll(collector.blocks.get(0).localVariables.stream()
                .filter(it -> it.index >= decorationData.handlersLocalStart).toList());
        target.method.maxLocals = Math.max(target.method.maxLocals,
                this.methodNode.maxLocals + decorationData.handlersLocalStart);
        target.method.maxStack = Math.max(target.method.maxStack, this.methodNode.maxStack);
    }

    protected class CollectingVisitor extends MethodVisitor {

        private final Target target;
        private final DecorationData decorationData;

        final List<MethodNode> blocks = new ArrayList<>();

        protected CollectingVisitor(int api, Target target, DecorationData decorationData) {
            super(api, null);
            this.target = target;
            this.decorationData = decorationData;
            this.step();
        }

        private AbstractInsnNode pendingCancel, pendingBlackhole;

        void next(AbstractInsnNode insn) {
            if (pendingCancel == insn) {
                super.visitInsn(target.returnType.getOpcode(Opcodes.IRETURN));
                pendingCancel = null;
            } else if (pendingBlackhole == insn) {
                pendingBlackhole = null;
            } else {
                if (pendingCancel == null && pendingBlackhole == null) {
                    if (decorationData.cancels.get(insn) != null) {
                        pendingCancel = decorationData.cancels.get(insn);
                    } else if (decorationData.blackholes.get(insn) != null) {
                        pendingBlackhole = decorationData.blackholes.get(insn);
                    } else {
                        insn.accept(this);
                    }
                }
            }
        }

        void step() {
            var mn = new MethodNode();
            blocks.add(mn);
            this.mv = mn;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (owner.equals(classNode.name) && name.equals(methodNode.name) && descriptor.equals(methodNode.desc)) {
                throw new InvalidInjectionException(info, "Inlining recursive method");
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            super.visitVarInsn(opcode, decorationData.lvtMap[varIndex]);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            super.visitIincInsn(decorationData.lvtMap[varIndex] + varIndex, increment);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end,
                                       int index) {
            super.visitLocalVariable(name, descriptor, signature, start, end, decorationData.lvtMap[index]);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack, decorationData.handlersLocalStart + maxLocals);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == decorationData.returnType.getOpcode(Opcodes.IRETURN)) {
                if (decorationData.hasEnd) {
                    super.visitJumpInsn(Opcodes.GOTO, decorationData.end.getLabel());
                }
            } else {
                super.visitInsn(opcode);
            }
        }
    }
}