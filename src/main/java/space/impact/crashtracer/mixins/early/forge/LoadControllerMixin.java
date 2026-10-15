package space.impact.crashtracer.mixins.early.forge;

import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.impact.crashtracer.bootstrap.TracerBootstrap;

@Mixin(value = LoadController.class, remap = false)
public abstract class LoadControllerMixin {
    @Inject(method = "transition", at = @At("RETURN"), require = 0)
    private void crashtracer$phase(LoaderState desired, boolean force, CallbackInfo ci) {
        TracerBootstrap.phase(desired.name());
    }

    @Inject(method = "errorOccurred", at = @At("HEAD"), require = 0)
    private void crashtracer$error(ModContainer mod, Throwable error, CallbackInfo ci) {
        try {
            String id = mod == null ? "unknown" : mod.getModId();
            TracerBootstrap.event("FML_ERROR", id, error.getClass().getName(), "FAILED");
            TracerBootstrap.capture("FML mod error: " + id, error, "fml-lifecycle");
        } catch (Throwable ignored) {}
    }
}
