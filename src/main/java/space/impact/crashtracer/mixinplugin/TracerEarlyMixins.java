package space.impact.crashtracer.mixinplugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.io.File;
import space.impact.crashtracer.bootstrap.TracerBootstrap;
import cpw.mods.fml.relauncher.FMLLaunchHandler;
import org.spongepowered.asm.mixin.Mixins;

/** Keep early bootstrap independent of Kotlin and optional mod classes. */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions("space.impact.crashtracer.mixinplugin")
public final class TracerEarlyMixins implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public String getMixinConfig() {
        Mixins.registerErrorHandlerClass("space.impact.crashtracer.mixinplugin.TracerMixinErrors");
        return "mixins.crashtracer.early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        List<String> mixins = new ArrayList<>(Arrays.asList("minecraft.CrashReportMixin",
                "minecraft.ServerTickMixin", "minecraft.NetworkManagerMixin"));
        if (FMLLaunchHandler.side().isClient()) mixins.add("minecraft.ClientTickMixin");
        for (String coremod : loadedCoreMods) {
            if (coremod.endsWith("Tweaker")) TracerBootstrap.tweaker(coremod);
            else TracerBootstrap.coremod(coremod, "unknown");
        }
        return mixins;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        Object location = data.get("mcLocation");
        if (location instanceof File) TracerBootstrap.initialize((File) location);
        try { space.impact.crashtracer.bootstrap.EarlyLaunchCapture.attach(); }
        catch (Throwable failure) { TracerBootstrap.event("ADAPTER", "early-launch-log", failure.getClass().getName(), "UNAVAILABLE"); }
        try {
            Object inventory = data.get("coremodList");
            if (inventory instanceof Iterable) for (Object wrapper : (Iterable<?>) inventory) {
                java.lang.reflect.Field pluginField = wrapper.getClass().getDeclaredField("coreModInstance");
                java.lang.reflect.Field locationField = wrapper.getClass().getDeclaredField("location");
                pluginField.setAccessible(true); locationField.setAccessible(true);
                Object plugin = pluginField.get(wrapper);
                Object origin = locationField.get(wrapper);
                if (origin == null && plugin.getClass().getProtectionDomain().getCodeSource() != null) {
                    origin = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
                }
                TracerBootstrap.coremod(plugin.getClass().getName(), origin == null ? "unknown" : origin.toString());
            }
        } catch (Throwable error) { TracerBootstrap.event("COREMOD", "inventory", error.getClass().getName(), "PARTIAL"); }
        try {
            for (String key : new String[] {"Tweaks", "TweakClasses"}) {
                Object values = net.minecraft.launchwrapper.Launch.blackboard.get(key);
                if (values instanceof Iterable) for (Object value : (Iterable<?>) values) {
                    if (value != null) TracerBootstrap.tweaker(value instanceof String ? (String) value : value.getClass().getName());
                }
            }
        } catch (Throwable ignored) {}
        if (TracerBootstrap.getOptions().experimentalTransformers) {
            // Mixingasm must classify the original transformer classes before any adapters exist.
            // TracerRuntime installs tracing at preInit, after early Mixin setup.
            TracerBootstrap.event("ADAPTER", "transformers", "Awaiting FML preInit; early transforms are not traced", "DEFERRED");
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
