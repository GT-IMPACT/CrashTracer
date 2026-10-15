package space.impact.crashtracer.mixins.early.minecraft;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.impact.crashtracer.bootstrap.Heartbeats;

@Mixin(Minecraft.class)
public abstract class ClientTickMixin {
    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void crashtracer$begin(CallbackInfo ci) { Heartbeats.begin("client"); }
    @Inject(method = "runTick", at = @At("RETURN"), require = 0)
    private void crashtracer$end(CallbackInfo ci) { Heartbeats.end("client"); }
}
