package io.izzel.arclight.mixin.injector;

import io.izzel.arclight.mixin.InlineRedirect;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.RedirectInjectionInfo;
import org.spongepowered.asm.mixin.transformer.MixinTargetContext;

@InjectionInfo.AnnotationType(InlineRedirect.class)
@InjectionInfo.HandlerPrefix("inlineRedirect")
public class InlineRedirectInfo extends RedirectInjectionInfo {

    public InlineRedirectInfo(MixinTargetContext mixin, MethodNode method, AnnotationNode annotation) {
        super(mixin, method, annotation);
    }

    @Override
    public void inject() {
        super.inject();
        try {
            InlineCallback.doInline(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
