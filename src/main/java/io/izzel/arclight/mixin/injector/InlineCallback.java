package io.izzel.arclight.mixin.injector;

import io.izzel.arclight.mixin.InlineMixin;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.transformer.MixinTargetContext;

import java.util.Collection;
import java.util.List;

public class InlineCallback {

    private static final String TYPE = Type.getDescriptor(InlineMixin.class);

    public static void inlineCallback(InjectionInfo info) throws Exception {
        if (info.getMethod() == null || info.getMethod().invisibleAnnotations == null) {
            return;
        }
        for (var ann : info.getMethod().invisibleAnnotations) {
            if (ann.desc.equals(TYPE)) {
                doInline(info);
            }
        }
    }

    public static void doInline(InjectionInfo info) throws Exception {
        var inliner = new MethodInliner(info.getMethod(), info.getClassNode());
        inliner.accept();
        var context = (MixinTargetContext) info.getMixin();
        prepareRemaining(info, context);
    }

    @SuppressWarnings("unchecked")
    private static void prepareRemaining(InjectionInfo current, MixinTargetContext context) throws Exception {
        var injectorsField = MixinTargetContext.class.getDeclaredField("injectors");
        injectorsField.setAccessible(true);
        var injectors = (List<InjectionInfo>) injectorsField.get(context);
        var readAnnotationMethod = InjectionInfo.class.getDeclaredMethod("readAnnotation");
        readAnnotationMethod.setAccessible(true);
        var selectorsField = InjectionInfo.class.getDeclaredField("selectors");
        selectorsField.setAccessible(true);
        var injectionPointsField = InjectionInfo.class.getDeclaredField("injectionPoints");
        injectionPointsField.setAccessible(true);
        boolean found = false;
        for (InjectionInfo info : injectors) {
            if (info == current) {
                found = true;
            } else if (found) {
                ((Collection<?>) selectorsField.get(info)).clear();
                ((Collection<?>) injectionPointsField.get(info)).clear();
                readAnnotationMethod.invoke(info);
                info.prepare();
                info.preInject();
            }
        }
        if (!found) {
            throw new RuntimeException("fuck");
        }
    }
}
