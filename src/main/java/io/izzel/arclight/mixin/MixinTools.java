package io.izzel.arclight.mixin;

import io.izzel.arclight.mixin.injector.DecoratorInfo;
import io.izzel.arclight.mixin.injector.EjectorInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;

public class MixinTools {

    public static void setup() {
        InjectionInfo.register(EjectorInfo.class);
        InjectionInfo.register(DecoratorInfo.class);
    }
}
