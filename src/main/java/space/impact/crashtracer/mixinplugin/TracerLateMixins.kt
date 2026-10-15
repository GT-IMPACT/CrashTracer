package space.impact.crashtracer.mixinplugin

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader
import com.gtnewhorizon.gtnhmixins.LateMixin
import cpw.mods.fml.relauncher.FMLLaunchHandler

@LateMixin
class TracerLateMixins : ILateMixinLoader {

    override fun getMixinConfig(): String = "mixins.crashtracer.late.json"

    override fun getMixins(loadedMods: MutableSet<String>): MutableList<String> {
        return if (FMLLaunchHandler.side().isClient && "bettercrashes" in loadedMods)
            mutableListOf("bettercrashes.CrashUtilsMixin") else mutableListOf()
    }
}
