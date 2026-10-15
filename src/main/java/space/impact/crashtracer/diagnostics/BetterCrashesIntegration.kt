package space.impact.crashtracer.diagnostics

import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.FMLCommonHandler
import space.impact.crashtracer.bootstrap.TracerBootstrap

class BetterCrashesIntegration : CrashIntegration {
    override fun isAvailable(): Boolean = FMLCommonHandler.instance().side.isClient && Loader.isModLoaded("bettercrashes")
    override fun initialize() {
        if (isAvailable()) TracerBootstrap.event("INTEGRATION", "bettercrashes", "Conditional late CrashUtils hook", "AVAILABLE")
        else if (Loader.isModLoaded("bettercrashes")) TracerBootstrap.event("INTEGRATION", "bettercrashes",
            "Dedicated server uses Minecraft/Forge capture; BetterCrashes output API is client-only.", "NOT_APPLICABLE")
    }
}
