@file:Suppress("unused")

package space.impact.crashtracer

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPostInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent
import cpw.mods.fml.common.event.FMLServerStartedEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.event.FMLServerStoppedEvent
import cpw.mods.fml.common.event.FMLServerStoppingEvent
import cpw.mods.fml.relauncher.Side
import space.impact.crashtracer.proxy.ClientProxy
import space.impact.crashtracer.proxy.CommonProxy
import space.impact.crashtracer.diagnostics.TracerRuntime

@Mod(
    modid = BuildConfig.MODID,
    version = BuildConfig.VERSION,
    name = BuildConfig.MODNAME,
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:forgelin;after:bettercrashes;",
)
object CrashTracerMod {

    val proxy = when (FMLCommonHandler.instance().side) {
        Side.CLIENT -> ClientProxy()
        Side.SERVER -> CommonProxy()
    }

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        TracerRuntime.phase("PRE_INITIALIZATION")
        proxy.preInit(event)
    }

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent) {
        TracerRuntime.phase("INITIALIZATION")
        proxy.init(event)
    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent) {
        TracerRuntime.phase("POST_INITIALIZATION")
        proxy.postInit(event)
        TracerRuntime.analyzePending()
    }

    @Mod.EventHandler
    fun serverAboutToStart(event: FMLServerAboutToStartEvent) {
        TracerRuntime.phase("SERVER_ABOUT_TO_START")
        proxy.serverAboutToStart(event)
    }

    @Mod.EventHandler
    fun serverStarting(event: FMLServerStartingEvent) {
        TracerRuntime.phase("SERVER_STARTING")
        proxy.serverStarting(event)
    }

    @Mod.EventHandler
    fun serverStarted(event: FMLServerStartedEvent) {
        TracerRuntime.phase("SERVER_STARTED")
        proxy.serverStarted(event)
    }

    @Mod.EventHandler
    fun serverStopping(event: FMLServerStoppingEvent) {
        TracerRuntime.phase("SERVER_STOPPING")
        proxy.serverStopping(event)
    }

    @Mod.EventHandler
    fun serverStopped(event: FMLServerStoppedEvent) {
        TracerRuntime.phase("SERVER_STOPPED")
        proxy.serverStopped(event)
    }
}
