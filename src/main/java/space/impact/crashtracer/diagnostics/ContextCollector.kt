package space.impact.crashtracer.diagnostics

import cpw.mods.fml.common.Loader
import net.minecraft.launchwrapper.Launch
import java.lang.management.ManagementFactory
import space.impact.crashtracer.bootstrap.TracerBootstrap
import space.impact.crashtracer.bootstrap.TransformerTrace

object ContextCollector {
    fun collect(phase: String): ContextSnapshot {
        val limitations = mutableListOf<String>()
        val mods = try {
            Loader.instance().modList.map {
                val file = it.source
                ModSnapshot(it.modId.take(256), it.name.take(256), it.version.take(256), file?.absolutePath,
                    file?.takeIf { source -> source.isFile }?.length(), file?.takeIf { source -> source.isFile }?.lastModified())
            }.take(2048)
        } catch (failure: Throwable) {
            limitations.add("Mod list unavailable: ${failure.javaClass.name}")
            emptyList()
        }
        val transformers = try {
            Launch.classLoader.transformers.map {
                if (it is TransformerTrace) it.describe() else {
                    val origin = try { it.javaClass.protectionDomain?.codeSource?.location?.toString() } catch (_: Exception) { null }
                    "${it.javaClass.name} @ ${origin ?: "unknown"}"
                }
            }.take(512)
        } catch (failure: Throwable) {
            limitations.add("Transformers unavailable: ${failure.javaClass.name}")
            emptyList()
        }
        // Only allow known memory flags; arbitrary JVM arguments can contain credentials.
        val memoryFlags = ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { it.matches(Regex("-Xm[sx][0-9]+[kKmMgG]?")) }
        val environment = linkedMapOf(
            "minecraft" to "1.7.10",
            "forge" to net.minecraftforge.common.ForgeVersion.getVersion(),
            "java" to System.getProperty("java.version", "unknown"),
            "vm" to System.getProperty("java.vm.name", "unknown"),
            "os" to System.getProperty("os.name", "unknown"),
            "osArch" to System.getProperty("os.arch", "unknown"),
            "memoryArguments" to memoryFlags.joinToString(" "),
        )
        val coremods = TracerBootstrap.coremods().map { "${it.key} @ ${it.value}" }
        val tweakers = try {
            (TracerBootstrap.tweakers() + listOf("TweakClasses", "Tweaks").flatMap { key ->
                (Launch.blackboard[key] as? Iterable<*>)?.mapNotNull { if (it is String) it else it?.javaClass?.name }.orEmpty()
            }).distinct().take(256)
        } catch (_: Exception) { emptyList() }
        if (coremods.isEmpty()) limitations.add("No early coremod inventory was available.")
        return ContextSnapshot(phase, mods, transformers, environment, limitations, coremods, tweakers)
    }
}
