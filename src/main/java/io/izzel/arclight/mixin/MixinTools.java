package io.izzel.arclight.mixin;

import io.izzel.arclight.mixin.injector.EjectorInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;

public class MixinTools {

    public static void setup() {
        InjectionInfo.register(EjectorInfo.class);
    }
}
