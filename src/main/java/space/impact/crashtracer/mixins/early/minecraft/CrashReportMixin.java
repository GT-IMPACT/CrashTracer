package space.impact.crashtracer.mixins.early.minecraft;

import net.minecraft.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import space.impact.crashtracer.bootstrap.TracerBootstrap;

@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    // Constructor RETURN is safe for Mixin; existing crash handlers retain their normal behavior.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void crashtracer$capture(String description, Throwable error, CallbackInfo ci) {
        // Forge deliberately constructs a throwaway report to initialize crash-report classes.
        if ("ThisIsFake".equals(description) && error != null && error.getClass() == Exception.class
                && "Not real".equals(error.getMessage())) {
            for (StackTraceElement frame : error.getStackTrace()) {
                if ("net.minecraftforge.common.MinecraftForge".equals(frame.getClassName())
                        && "initialize".equals(frame.getMethodName())) return;
            }
        }
        // SplashProgress prints hardware specs through a report during normal client startup.
        if ("Loading screen debug info".equals(description) && error != null
                && error.getClass().getName().startsWith("cpw.mods.fml.client.SplashProgress$")
                && "This is just a prompt for computer specs to be printed. THIS IS NOT A ERROR".equals(error.getMessage())) {
            for (StackTraceElement frame : error.getStackTrace()) {
                if ("cpw.mods.fml.client.SplashProgress".equals(frame.getClassName())
                        && "start".equals(frame.getMethodName())) return;
            }
        }
        try { TracerBootstrap.capture(description, error, "minecraft-crash-report"); }
        catch (Throwable ignored) {}
    }
}
