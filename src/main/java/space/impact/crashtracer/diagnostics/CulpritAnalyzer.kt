package space.impact.crashtracer.diagnostics

import java.io.File
import java.util.jar.JarFile

/** Index class entries without loading or initializing any suspect classes. */
class CulpritAnalyzer(mods: List<ModSnapshot>) {
    private val sources = mods.filter { it.source != null && it.id !in setOf("mcp", "FML", "Forge") }.groupBy { it.source!! }

    fun analyze(snapshot: ExceptionSnapshot): List<Suspect> {
        val classes = snapshot.nodes.flatMap { it.frames }.mapNotNull { frame ->
            val method = frame.substringBefore('(')
            method.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        }.toSet()
        val evidence = linkedMapOf<String, MutableSet<String>>()
        for ((path, mods) in sources) {
            try {
                val file = File(path)
                if (mods.any { (it.size != null && file.length() != it.size) ||
                    (it.modifiedAt != null && file.lastModified() != it.modifiedAt) }) continue
                val owned = if (file.isDirectory) {
                    classes.filter { File(file, it.replace('.', '/') + ".class").isFile }
                } else {
                    JarFile(file).use { jar -> classes.filter { jar.getJarEntry(it.replace('.', '/') + ".class") != null } }
                }
                for (mod in mods) for (name in owned) {
                    evidence.getOrPut(mod.id) { linkedSetOf() }.add("Stack frame class $name is present in $path")
                }
            } catch (_: Exception) {
                // Inaccessible sources cannot be used as evidence.
            }
        }
        return evidence.map { (mod, facts) -> Suspect(mod, "LOW", facts.take(32)) }.sortedBy { it.modId }
    }

    fun analyze(report: IncidentReport): List<Suspect> {
        val candidates = analyze(report.exceptions).associateBy { it.modId }.toMutableMap()
        fun add(id: String, confidence: String, fact: String) {
            val old = candidates[id]
            val weights = mapOf("LOW" to 1, "MEDIUM" to 2, "HIGH" to 3)
            val level = if ((weights[old?.confidence] ?: 0) > (weights[confidence] ?: 0)) old!!.confidence else confidence
            candidates[id] = Suspect(id, level, (old?.evidence.orEmpty() + fact).distinct().take(64))
        }
        for (mixin in report.forensics?.mixins.orEmpty()) {
            if (!mixin.status.endsWith("FAILED")) continue
            if (report.source != "mixin-error" || !report.description.contains("${mixin.mixin} config=${mixin.config}")) continue
            val owners = mixin.owners.ifEmpty { ownersOf(mixin.mixin.replace('.', '/') + ".class", mixin.config) }
            for (owner in owners) add(owner, "HIGH",
                "Observed ${mixin.status}: ${mixin.mixin}, config ${mixin.config}, target ${mixin.target}. This identifies the failing transformation, not necessarily its root cause.")
        }
        for (event in report.forensics?.events.orEmpty().filter { it.size >= 5 && it[1] == "TRANSFORM" && it[4].startsWith("FAILED") }) {
            if (report.source != "asm-transformer" || report.description != "Transformer ${event[3]} failed on ${event[2]}") continue
            for (owner in ownersOf(event[3].replace('.', '/') + ".class", "")) {
                add(owner, "MEDIUM", "Transformer ${event[3]} failed while processing ${event[2]}: ${event[4]}")
            }
        }
        for (event in report.forensics?.events.orEmpty().filter { it.size >= 5 && it[1] == "FML_ERROR" }) {
            if (report.source != "fml-lifecycle" || report.description != "FML mod error: ${event[2]}") continue
            if (sources.values.flatten().any { it.id == event[2] }) add(event[2], "MEDIUM",
                "FML recorded a lifecycle error against this mod: ${event[3]}. A dependency may be the actual cause.")
        }
        return candidates.values.sortedWith(compareByDescending<Suspect> { mapOf("HIGH" to 3, "MEDIUM" to 2, "LOW" to 1)[it.confidence] ?: 0 }.thenBy { it.modId })
    }

    private fun ownersOf(classEntry: String, config: String): List<String> {
        val result = mutableListOf<String>()
        for ((path, mods) in sources) try {
            val file = File(path)
            if (mods.any { (it.size != null && file.length() != it.size) || (it.modifiedAt != null && file.lastModified() != it.modifiedAt) }) continue
            val found = if (file.isDirectory) File(file, classEntry).isFile || (config.isNotEmpty() && File(file, config).isFile)
            else JarFile(file).use { it.getJarEntry(classEntry) != null || (config.isNotEmpty() && it.getJarEntry(config) != null) }
            if (found) result.addAll(mods.map { it.id })
        } catch (_: Exception) {}
        return result.distinct()
    }
}
