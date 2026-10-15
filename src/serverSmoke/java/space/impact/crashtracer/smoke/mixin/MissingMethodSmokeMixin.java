package space.impact.crashtracer.smoke.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class MissingMethodSmokeMixin {
    @Inject(method = "crashtracer$nonexistentMethod", at = @At("HEAD"), require = 1, remap = false)
    private void crashtracer$invalidTarget(CallbackInfo callback) {}
}
