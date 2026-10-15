package space.impact.crashtracer.smoke.mixinplugin;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions("space.impact.crashtracer.smoke.mixinplugin")
public final class SmokeEarlyMixins implements IFMLLoadingPlugin, IEarlyMixinLoader {
    public String getMixinConfig() { return "mixins.crashtracer.smoke.json"; }
    public List<String> getMixins(Set<String> loaded) {
        if ("early-fml".equals(System.getProperty("crashtracer.smoke"))) {
            try { Class.forName("cpw.mods.fml.common.LoadController", true, net.minecraft.launchwrapper.Launch.classLoader); }
            catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
            System.out.println("CRASHTRACER_SMOKE_EARLY_FML_LOADED");
            return Collections.emptyList();
        }
        return Collections.singletonList("mixin".equals(System.getProperty("crashtracer.smoke")) ? "BrokenSmokeMixin" : "MissingMethodSmokeMixin");
    }
    public String[] getASMTransformerClass() { return new String[0]; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public String getAccessTransformerClass() { return null; }
    public void injectData(Map<String, Object> data) {}
}
