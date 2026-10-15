package space.impact.crashtracer.diagnostics

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.event.world.WorldEvent
import space.impact.crashtracer.bootstrap.TracerBootstrap

object RuntimeEvents {
    @Volatile var world: Map<String, String> = emptyMap()
        private set

    @Synchronized @SubscribeEvent fun loaded(event: WorldEvent.Load) {
        try {
            val key = "${if (event.world.isRemote) "client" else "server"}:${event.world.provider.dimensionId}"
            world = world + (key to event.world.provider.javaClass.name)
            TracerBootstrap.event("WORLD", key, event.world.provider.javaClass.name, "LOADED")
        } catch (_: Throwable) {}
    }

    @Synchronized @SubscribeEvent fun unloaded(event: WorldEvent.Unload) {
        try {
            val key = "${if (event.world.isRemote) "client" else "server"}:${event.world.provider.dimensionId}"
            TracerBootstrap.event("WORLD", key, "", "UNLOADED")
            world = world - key
        } catch (_: Throwable) {}
    }
}
