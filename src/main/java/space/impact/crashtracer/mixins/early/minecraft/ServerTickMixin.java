package space.impact.crashtracer.mixins.early.minecraft;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.impact.crashtracer.bootstrap.Heartbeats;

@Mixin(MinecraftServer.class)
public abstract class ServerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void crashtracer$begin(CallbackInfo ci) { Heartbeats.begin("server"); }
    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void crashtracer$end(CallbackInfo ci) { Heartbeats.end("server"); }
}
