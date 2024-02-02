package io.izzel.arclight.mixin.ap;

import io.izzel.arclight.mixin.injector.EjectorInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.util.logging.MessageRouter;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Collections;
import java.util.Set;

public class EjectProvider extends AbstractProcessor {

    static {
        try {
            MessageRouter.setMessager(new StdoutMessenger());
        } catch (NoClassDefFoundError ignored) {
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        InjectionInfo.register(EjectorInfo.class);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.emptySet();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_8;
    }
}
