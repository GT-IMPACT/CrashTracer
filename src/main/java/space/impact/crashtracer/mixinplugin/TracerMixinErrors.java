package space.impact.crashtracer.mixinplugin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import space.impact.crashtracer.bootstrap.TracerBootstrap;

public final class TracerMixinErrors implements IMixinErrorHandler {
    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable error, IMixinInfo mixin, ErrorAction action) {
        record("PREPARE", config == null ? "unknown" : config.getName(), error, mixin);
        return action;
    }

    @Override
    public ErrorAction onApplyError(String target, Throwable error, IMixinInfo mixin, ErrorAction action) {
        record("APPLY", target, error, mixin);
        return action;
    }

    private void record(String stage, String target, Throwable error, IMixinInfo mixin) {
        try {
            String detail = mixin == null ? "unknown" : mixin.getClassName() + " config=" + mixin.getConfig().getName();
            TracerBootstrap.event("MIXIN", target, detail, stage + "_FAILED");
            TracerBootstrap.capture("Mixin " + stage + " failed: " + detail + " target=" + target, error, "mixin-error");
        } catch (Throwable ignored) {}
    }
}
