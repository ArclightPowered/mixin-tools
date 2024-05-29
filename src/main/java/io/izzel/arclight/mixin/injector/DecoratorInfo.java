package io.izzel.arclight.mixin.injector;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.code.Injector;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionNodes;
import org.spongepowered.asm.mixin.transformer.MixinTargetContext;

import io.izzel.arclight.mixin.Decorate;

import java.util.List;

@InjectionInfo.AnnotationType(Decorate.class)
@InjectionInfo.HandlerPrefix("decorate")
public class DecoratorInfo extends InjectionInfo {

    static final String DECORATOR_ORIGINAL_INJECTION_POINT = "DECORATOR_ORIGINAL_INJECTION_POINT";

    public DecoratorInfo(MixinTargetContext mixin, MethodNode method, AnnotationNode annotation) {
        super(mixin, method, annotation);
    }

    @Override
    protected Injector parseInjector(AnnotationNode injectAnnotation) {
        return new Decorator(this);
    }

    @Override
    public void prepare() {
        super.prepare();
        for (var nodes : this.targetNodes.values()) {
            for (var node : nodes) {
                node.decorate(DECORATOR_ORIGINAL_INJECTION_POINT, this.injectionPoints.get(0));
            }
        }
    }

    @Override
    protected String getDescription() {
        return "Decorate";
    }
}
