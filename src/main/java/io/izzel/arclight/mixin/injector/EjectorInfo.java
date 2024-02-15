package io.izzel.arclight.mixin.injector;

import io.izzel.arclight.mixin.Eject;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.code.Injector;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.transformer.MixinTargetContext;

@InjectionInfo.AnnotationType(Eject.class)
@InjectionInfo.HandlerPrefix("eject")
public class EjectorInfo extends InjectionInfo {

    public EjectorInfo(MixinTargetContext mixin, MethodNode method, AnnotationNode annotation) {
        super(mixin, method, annotation);
    }

    @Override
    protected Injector parseInjector(AnnotationNode injectAnnotation) {
        return new Ejector(this);
    }

    @Override
    protected String getDescription() {
        return "Eject";
    }

    @Override
    public void inject() {
        super.inject();
    }
}
