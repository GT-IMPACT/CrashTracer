package space.impact.crashtracer.mixins.late.bettercrashes;

import net.minecraft.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.impact.crashtracer.bootstrap.TracerBootstrap;

@Mixin(targets = "vfyjxf.bettercrashes.utils.CrashUtils", remap = false)
public abstract class CrashUtilsMixin {
    @Inject(method = "outputReport", at = @At("HEAD"), require = 0)
    private static void crashtracer$output(CrashReport report, CallbackInfo ci) {
        try {
            TracerBootstrap.event("BETTER_CRASHES", "outputReport", report.getDescription(), "REPORT");
            TracerBootstrap.capture(report.getDescription(), report.getCrashCause(), "bettercrashes-output");
        } catch (Throwable ignored) {}
    }
}
