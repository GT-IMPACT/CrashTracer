package space.impact.crashtracer.smoke.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Deliberately missing call site; opt-in mixin scenario must fail without changing ErrorAction. */
@Mixin(value = MinecraftServer.class, remap = false)
public abstract class BrokenSmokeMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Ljava/lang/System;crashTracerMissingCall()V"), require = 1, remap = false)
    private void crashtracer$missingCall(CallbackInfo callback) {}
}
