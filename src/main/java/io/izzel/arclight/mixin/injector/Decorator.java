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
import org.spongepowered.asm.mixin.injection.points.MethodHead;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionNodes.InjectionNode;
import org.spongepowered.asm.mixin.injection.struct.Target;
import org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException;
import org.spongepowered.asm.util.Annotations;
import org.spongepowered.asm.util.Locals;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public class Decorator extends Injector {

    private static final String DECORATION_LOCALS = "DECORATION_LOCALS";
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
        INVOKE, FIELD, NEW, RETURN, HEAD
    }

    protected static class DecorationData extends InjectorData {

        final AbstractInsnNode node, nodeEnd;
        final DecorationTarget decorationTarget;
        final Type returnType;
        final Type[] handlerArgs;

        final AbstractInsnNode callsiteDecl, callsiteInvoke;
        final Map<AbstractInsnNode, MethodInsnNode> cancels, blackholes; // decl -> invoke

        final boolean requireFrame;

        final Label begin = new Label(), end = new Label();
        final LocalVariableNode[] locals;

        boolean hasEnd = false;
        int handlerLocalsStart, handlerStackStart;
        int handlerLocalsOffset;
        List<Object> targetLocals;
        int[] lvtMap;

        DecorationData(Target target, InjectionNode injectionNode, AbstractInsnNode callsiteDecl,
                       AbstractInsnNode callsiteInvoke, Map<AbstractInsnNode, MethodInsnNode> cancels,
                       Map<AbstractInsnNode, MethodInsnNode> blackholes, boolean requireFrame, LocalVariableNode[] locals) {
            super(target);
            this.node = injectionNode.getCurrentTarget();
            this.callsiteDecl = callsiteDecl;
            this.callsiteInvoke = callsiteInvoke;
            this.cancels = cancels;
            this.blackholes = blackholes;
            this.requireFrame = requireFrame;
            this.locals = locals;
            if (injectionNode.getDecoration(DecoratorInfo.DECORATOR_ORIGINAL_INJECTION_POINT) instanceof MethodHead) {
                this.returnType = Type.VOID_TYPE;
                this.handlerArgs = new Type[]{};
                this.nodeEnd = this.node;
                this.decorationTarget = DecorationTarget.HEAD;
            } else if (this.node instanceof MethodInsnNode mn) {
                this.returnType = Type.getReturnType(mn.desc);
                var targetArgs = Type.getArgumentTypes(mn.desc);
                var handlerArgs = targetArgs;
                if (this.node.getOpcode() != Opcodes.INVOKESTATIC) {
                    handlerArgs = new Type[targetArgs.length + 1];
                    handlerArgs[0] = Type.getObjectType(mn.owner);
                    System.arraycopy(targetArgs, 0, handlerArgs, 1, targetArgs.length);
                }
                this.handlerArgs = handlerArgs;
                this.nodeEnd = this.node;
                this.decorationTarget = DecorationTarget.INVOKE;
            } else if (this.node instanceof FieldInsnNode fn) {
                switch (this.node.getOpcode()) {
                    case Opcodes.GETFIELD -> {
                        this.returnType = Type.getType(fn.desc);
                        this.handlerArgs = new Type[]{Type.getObjectType(fn.owner)};
                    }
                    case Opcodes.GETSTATIC -> {
                        this.returnType = Type.getType(fn.desc);
                        this.handlerArgs = new Type[]{};
                    }
                    case Opcodes.PUTFIELD -> {
                        this.returnType = Type.VOID_TYPE;
                        this.handlerArgs = new Type[]{Type.getObjectType(fn.owner),
                            Type.getType(fn.desc)};
                    }
                    case Opcodes.PUTSTATIC -> {
                        this.returnType = Type.VOID_TYPE;
                        this.handlerArgs = new Type[]{Type.getType(fn.desc)};
                    }
                    default -> throw new IllegalArgumentException("Unknown opcode " + this.node.getOpcode());
                }
                this.nodeEnd = this.node;
                this.decorationTarget = DecorationTarget.FIELD;
            } else if (this.node instanceof TypeInsnNode tn && tn.getOpcode() == Opcodes.NEW) {
                this.returnType = Type.getObjectType(tn.desc);
                var initNode = target.findInitNodeFor(tn);
                if (initNode == null) {
                    throw new IllegalArgumentException(
                        "No <init> call for NEW at bci " + target.method.instructions.indexOf(tn));
                }
                this.handlerArgs = Type.getArgumentTypes(initNode.desc);
                this.nodeEnd = initNode;
                this.decorationTarget = DecorationTarget.NEW;
            } else if (this.node instanceof InsnNode insn && insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) {
                this.returnType = Type.VOID_TYPE;
                this.handlerArgs = new Type[]{target.returnType};
                this.nodeEnd = this.node;
                this.decorationTarget = DecorationTarget.RETURN;
            } else {
                throw new UnsupportedOperationException("Invalid target type " + this.node);
            }
        }
    }

    @Override
    protected void preInject(Target target, InjectionNode node) {
        node.decorate(DECORATION_LOCALS, Locals.getLocalsAt(target.classNode, target.method, node.getCurrentTarget(), Locals.Settings.DEFAULT));
    }

    @Override
    protected void inject(Target target, InjectionNode node) {
        if (node.isReplaced()) {
            throw new UnsupportedOperationException("Indirect target failure for " + this.info);
        }
        this.methodNode.instructions.resetLabels();
        this.checkTargetForNode(target, node, InjectionPoint.RestrictTargetLevel.ALLOW_ALL);
        var decorationData = createDecorationData(target, node);
        this.guardInline(target, node, decorationData, decorationData.handlerArgs);
        decorationData.lvtMap = this.prepareLvtMapping(target, decorationData, node.getDecoration(DECORATION_LOCALS));
        this.performInline(target, node, decorationData);
        this.info.addCallbackInvocation(this.methodNode);
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
                "Open DecorationOps in @Decorate: " + lastDecl + " at bci " + this.methodNode.instructions.indexOf(callsiteDecl));
        }
        for (var invoke : cancels.values()) {
            var methodType = Type.getMethodType(invoke.desc);
            var argumentTypes = methodType.getArgumentTypes();
            if ((target.returnType.equals(Type.VOID_TYPE) && argumentTypes.length != 0) ||
                (!target.returnType.equals(Type.VOID_TYPE) && (argumentTypes.length != 1 || !argumentTypes[0].equals(target.returnType)))) {
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
        var data = new DecorationData(target, node, callsiteDecl, callsiteInvoke, cancels,
            blackholes, requireFrame, node.getDecoration(DECORATION_LOCALS));
        if (!data.returnType.equals(Type.getReturnType(this.methodNode.desc))) {
            throw new InvalidInjectionException(this.info, "Return type mismatch: expect " + data.returnType
                + ", found " + Type.getReturnType(this.methodNode.desc));
        }
        var argTypes = Type.getArgumentTypes(this.methodNode.desc);
        var handlerArgs = data.handlerArgs;
        for (var i = 0; i < handlerArgs.length; i++) {
            var handlerArg = handlerArgs[i];
            if (!argTypes[i].equals(handlerArg)) {
                throw new InvalidInjectionException(this.info,
                    "Callback argument type mismatch at " + i + ": expect " + handlerArg + ", found " + argTypes[i]);
            }
        }
        return data;
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
        decorationData.handlerLocalsStart = handlerStartIndex;
        decorationData.handlerStackStart = adapter.stack.size();
        for (int i = 0, lvIndex = handlerStartIndex; i < handlerTypes.length; i++) {
            beforeDecorate.insert(new VarInsnNode(handlerTypes[i].getOpcode(Opcodes.ISTORE), lvIndex));
            lvIndex += handlerTypes[i].getSize();
        }
        // 2. store unused stack elements, likely 0
        for (int i = currentStack.size() - handlerTypes.length - 1, lvIndex = adapter.locals.size(); i >= 0; i--) {
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
        var callbackLocals = Arrays.stream(decorationData.handlerArgs).map(EnhancedAnalyzerAdapter::getFrameItem).toArray();
        var mergedLocals = new Object[currentLocal.size() + currentStack.size() + callbackArgs.length];
        System.arraycopy(currentLocal.toArray(), 0, mergedLocals, 0, currentLocal.size());
        System.arraycopy(currentStack.toArray(), 0, mergedLocals, currentLocal.size(), currentStack.size());
        System.arraycopy(callbackLocals, 0, mergedLocals, currentLocal.size() + currentStack.size(),
            callbackLocals.length);
        beforeDecorate.add(new LabelNode(decorationData.begin));
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
                afterDecorate.insert(new VarInsnNode(Type.getReturnType(this.methodNode.desc).getOpcode(Opcodes.ISTORE), handlerStartIndex));
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

    private int[] prepareLvtMapping(Target target, DecorationData decorationData, LocalVariableNode[] lvns) {
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
            lvtMap[lvIndex] = lvIndex - offset + decorationData.handlerLocalsStart;
            lvIndex += this.methodArgs[i].getSize();
        }
        var localsStart = targetArgsStart + target.arguments.length;
        for (int i = targetArgsStart; i < Math.min(this.methodArgs.length, localsStart); i++) {
            lvtMap[lvIndex] = offset;
            lvIndex += this.methodArgs[i].getSize();
            offset += this.methodArgs[i].getSize();
        }
        var handlerLocalsOffset = offset;
        for (int i = localsStart; i < this.methodArgs.length; i++) {
            handlerLocalsOffset += this.methodArgs[i].getSize();
        }
        decorationData.handlerLocalsOffset = handlerLocalsOffset;
        for (int i = localsStart; i < this.methodArgs.length; i++) {
            lvtMap[lvIndex] = findLv(target, decorationData, i, lvns);
            lvIndex += this.methodArgs[i].getSize();
            offset += this.methodArgs[i].getSize();
        }
        for (int i = lvIndex; i < this.methodNode.maxLocals; i++) {
            lvtMap[lvIndex] = lvIndex - offset + decorationData.handlerLocalsStart;
            lvIndex++;
        }
        return lvtMap;
    }

    private int findLv(Target target, DecorationData decorationData, int i, LocalVariableNode[] locals) {
        var type = this.methodArgs[i];
        AnnotationNode localNode;
        if (this.methodNode.invisibleAnnotableParameterCount > i &&
            (localNode = this.methodNode.invisibleParameterAnnotations[i].stream()
                .filter(it -> it.desc.equals(LOCAL_DESC)).findAny().orElse(null)) != null) {
            var index = Annotations.<Integer>getValue(localNode, "ordinal");
            var allocate = Annotations.<String>getValue(localNode, "allocate");
            if (index != null && allocate != null) {
                throw new InvalidInjectionException(this.info, "Only one of 'ordinal' and 'allocate' can exist on @Local at parameter " + i);
            }
            if (index != null) {
                var lvns = Arrays.stream(locals).filter(it -> Type.getType(it.desc).equals(type)).toList();
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
            } else if (allocate != null) {
                var allocated = target.method.localVariables.stream().filter(it -> it instanceof AllocatedLocalVariableNode al && al.id.equals(allocate)).findFirst();
                if (allocated.isPresent()) {
                    if (!allocated.get().desc.equals(this.methodArgs[i].getDescriptor())) {
                        throw new InvalidInjectionException(this.info, "@Local allocate has different desc "
                            + this.methodArgs[i].getDescriptor() + " and " + allocated.get().desc);
                    }
                    return allocated.get().index;
                } else {
                    var allocateStart = Math.max(target.method.maxLocals,
                        this.methodNode.maxLocals - decorationData.handlerLocalsOffset + decorationData.handlerLocalsStart);
                    target.method.maxLocals = allocateStart + this.methodArgs[i].getSize();
                    target.method.localVariables.add(new AllocatedLocalVariableNode(allocate, this.methodArgs[i].getDescriptor(),
                        null, new LabelNode(decorationData.begin), new LabelNode(decorationData.end), allocateStart));
                    return allocateStart;
                }
            } else {
                throw new InvalidInjectionException(this.info, "Invalid @Local at parameter " + i);
            }
        } else {
            throw new InvalidInjectionException(this.info, "@Local not exist at local " + i + ": " + type);
        }
    }

    private void performInline(Target target, InjectionNode node, DecorationData decorationData) {
        var collector = new CollectingVisitor(Opcodes.ASM9, target, decorationData);
        for (var lvn : this.methodNode.localVariables) {
            lvn.accept(collector);
        }
        for (var tryCatch : this.methodNode.tryCatchBlocks) {
            tryCatch.accept(collector);
        }
        switch (decorationData.decorationTarget) {
            case INVOKE, FIELD, HEAD, RETURN -> {
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
                collector.visitEnd();
                target.insns.insertBefore(node.getCurrentTarget(), collector.blocks.get(0).instructions);
                target.insns.insert(decorationData.nodeEnd, collector.blocks.get(1).instructions);
            }
            case NEW -> {
                for (var insn : this.methodNode.instructions) {
                    if (insn == decorationData.callsiteDecl) {
                        collector.step();
                    } else if (insn == decorationData.callsiteInvoke) {
                        collector.step();
                    } else {
                        collector.next(insn);
                    }
                }
                collector.visitEnd();
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
            default ->
                throw new InvalidInjectionException(this.info, "Unknown decoration target: " + decorationData.decorationTarget);
        }
        var tcns = collector.blocks.get(0).tryCatchBlocks;
        if (tcns != null) {
            target.method.tryCatchBlocks.addAll(this.findTryCatchIndex(target.method, tcns), tcns);
        }
        var lvns = collector.blocks.get(0).localVariables;
        if (lvns != null) {
            target.method.localVariables.addAll(lvns.stream().filter(it -> it.index >= decorationData.handlerLocalsStart).toList());
        }
        target.method.maxLocals = Math.max(target.method.maxLocals,
            this.methodNode.maxLocals - decorationData.handlerLocalsOffset + decorationData.handlerLocalsStart);
        target.method.maxStack = Math.max(target.method.maxStack,
            this.methodNode.maxStack + decorationData.handlerStackStart);
    }

    private int findTryCatchIndex(MethodNode target, List<TryCatchBlockNode> tcns) {
        var labelIndexes = new HashMap<AbstractInsnNode, Integer>();
        for (var node : target.instructions) {
            if (node.getType() == LabelNode.LABEL) {
                labelIndexes.put(node, target.instructions.indexOf(node));
            }
        }
        for (var i = target.tryCatchBlocks.size() - 1; i >= 0; i--) {
            var tcn = target.tryCatchBlocks.get(i);
            if (tcns.stream().anyMatch(it -> labelIndexes.get(it.start) <= labelIndexes.get(tcn.start) && labelIndexes.get(it.end) >= labelIndexes.get(tcn.end))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static class AllocatedLocalVariableNode extends LocalVariableNode {

        private final String id;

        public AllocatedLocalVariableNode(String id, String descriptor, String signature, LabelNode start, LabelNode end, int index) {
            super("decorator_" + id, descriptor, signature, start, end, index);
            this.id = id;
        }

        @Override
        public void accept(MethodVisitor methodVisitor) {
        }
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
        private boolean cancelReturn = false;

        void next(AbstractInsnNode insn) {
            if (pendingCancel == insn) {
                super.visitInsn(target.returnType.getOpcode(Opcodes.IRETURN));
                pendingCancel = null;
                cancelReturn = true;
            } else if (pendingBlackhole == insn) {
                pendingBlackhole = null;
            } else {
                if (pendingBlackhole == null) {
                    if (pendingCancel == null && decorationData.cancels.get(insn) != null) {
                        pendingCancel = decorationData.cancels.get(insn);
                    } else if (decorationData.blackholes.get(insn) != null) {
                        pendingBlackhole = decorationData.blackholes.get(insn);
                    } else if (cancelReturn) {
                        if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) {
                            cancelReturn = false;
                        }
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
            super.visitIincInsn(decorationData.lvtMap[varIndex], increment);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (type == Opcodes.F_FULL || type == Opcodes.F_NEW) {
                var mergedNum = numLocal + decorationData.targetLocals.size();
                var mergedLocal = new Object[mergedNum];
                System.arraycopy(decorationData.targetLocals.toArray(), 0, mergedLocal, 0, decorationData.targetLocals.size());
                System.arraycopy(local, 0, mergedLocal, decorationData.targetLocals.size(), numLocal);
                super.visitFrame(type, mergedNum, mergedLocal, numStack, stack);
            } else {
                super.visitFrame(type, numLocal, local, numStack, stack);
            }
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end,
                                       int index) {
            super.visitLocalVariable(name, descriptor, signature, start, end, decorationData.lvtMap[index]);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == decorationData.returnType.getOpcode(Opcodes.IRETURN)) {
                if (decorationData.hasEnd) {
                    super.visitJumpInsn(Opcodes.GOTO, decorationData.end);
                }
            } else {
                super.visitInsn(opcode);
            }
        }

        @Override
        public void visitEnd() {
            if (decorationData.hasEnd) {
                super.visitLabel(decorationData.end);
            }
            super.visitEnd();
        }
    }
}
