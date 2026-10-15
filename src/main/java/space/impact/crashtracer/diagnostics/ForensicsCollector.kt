package space.impact.crashtracer.diagnostics

import org.spongepowered.asm.mixin.Mixins
import space.impact.crashtracer.bootstrap.TracerBootstrap

class ForensicsCollector(private val mods: List<ModSnapshot>) : EvidenceProvider {
    override fun collect(classes: Set<String>): ForensicsSnapshot {
        val records = mutableListOf<MixinRecord>()
        val limitations = mutableListOf<String>()
        try {
            for (config in Mixins.getConfigs().take(256)) {
                if (records.size >= 2048) { limitations.add("Mixin registry snapshot truncated at 2048 associations."); break }
                val metadata = config.config
                for (target in metadata.targets.take((2048 - records.size).coerceAtMost(128))) {
                    records.add(MixinRecord(metadata.name, metadata.mixinPackage + "*", target,
                        "REGISTERED", metadata.priority, metadata.cleanSourceId, emptyList()))
                }
            }
        } catch (error: Throwable) { limitations.add("Mixin registry snapshot unavailable: ${error.javaClass.name}") }
        for (target in classes.take(128)) {
            if (records.size >= 4096) { limitations.add("Mixin lookup truncated at 4096 associations."); break }
            try {
                for (mixin in Mixins.getMixinsForClass(target).take(128)) {
                    val config = mixin.config
                    // Resource origin lookup does not load or initialize the mixin class.
                    val resource = javaClass.classLoader.getResource(mixin.className.replace('.', '/') + ".class")?.toString()
                    val owners = mods.filter { mod ->
                        mod.source?.let { resource?.startsWith("jar:" + java.io.File(it).toURI().toURL().toString() + "!/") } == true
                    }.map { it.id }
                    records.add(MixinRecord(config.name, mixin.className, target, "ASSOCIATED",
                        mixin.priority, resource, owners))
                }
            } catch (failure: Throwable) {
                limitations.add("Mixin lookup unavailable for $target: ${failure.javaClass.name}")
            }
        }
        val events = TracerBootstrap.events().map { it.toList() }
        for (event in events.filter { it[1] == "MIXIN" }) {
            records.add(MixinRecord(event[3].substringAfter("config=", "unknown"),
                event[3].substringBefore(" config="), event[2], event[4], null, null, emptyList()))
        }
        limitations.add("ASSOCIATED records do not prove successful application or culpability.")
        return ForensicsSnapshot(records.distinctBy { listOf(it.config, it.mixin, it.target, it.status) }, events, limitations)
    }
}
