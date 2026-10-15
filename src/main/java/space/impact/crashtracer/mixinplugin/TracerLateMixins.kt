package space.impact.crashtracer.mixinplugin

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader
import com.gtnewhorizon.gtnhmixins.LateMixin

@LateMixin
class TracerLateMixins : ILateMixinLoader {

    override fun getMixinConfig(): String = "mixins.crashtracer.late.json"

    override fun getMixins(loadedMods: MutableSet<String>): MutableList<String> {
        // Add optional mod mixins only after checking their IDs in loadedMods.
        // Never reference BetterCrashes classes from this loader directly.
        return mutableListOf()
    }
}
