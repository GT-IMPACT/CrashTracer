package space.impact.crashtracer.diagnostics

import java.io.File
import java.security.MessageDigest

data class AnalysisResult(val schemaVersion: Int = 1, val incidentId: String, val fingerprint: String,
    val rootException: String?, val suspects: List<Suspect>, val findings: List<String>,
    val mappings: List<String>, val limitations: List<String>)

class AnalysisEngine(private val mappings: LocalMappings) {
    fun analyze(directory: File, report: IncidentReport, maxBytes: Long): AnalysisResult {
        val suspects = CulpritAnalyzer(report.context.mods).analyze(report)
        val root = report.exceptions.rootCause?.let { id -> report.exceptions.nodes.firstOrNull { it.id == id } }
        val stable = report.exceptions.nodes.joinToString("\n") { node ->
            node.type + "\n" + node.frames.take(32).joinToString("\n") { mappings.resolveFrame(it).substringBefore('(') }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val findings = mutableListOf<String>()
        if (report.threads.any { it.deadlocked }) findings.add("JVM reported a deadlock. Inspect threads.txt for owners and locks.")
        report.threads.filter { it.lock != null && it.lockOwnerId != null && it.lockOwnerAlive == false }.take(16).forEach {
            findings.add("Thread ${it.name} (${it.id}) waits on ${it.lock}, owned by ${it.lockOwner} (${it.lockOwnerId}), which was no longer live when sampled. A terminated owner may leave a lock held after a crash; this is not proof of a JVM deadlock cycle. Restart may be required; Crash Tracer does not release other mods' locks.")
        }
        val types = report.exceptions.nodes.map { it.type }
        if (types.any { it.endsWith("OutOfMemoryError") }) findings.add("Memory exhaustion: inspect heap/JVM limits; the allocation site does not prove a memory leak owner.")
        if (types.any { it.endsWith("ClassNotFoundException") || it.endsWith("NoClassDefFoundError") }) findings.add("Class loading failure: check missing dependencies, versions and earlier initialization errors in cause/suppressed.")
        if (types.any { it.endsWith("VerifyError") }) findings.add("Bytecode verification failed. Recorded transformer history may help identify an incompatible transformation.")
        if (report.source == "jvm-native-log") findings.add("Native JVM failure: inspect the problematic frame, loaded libraries and VM state in the imported hs_err log.")
        val changed = report.forensics?.events.orEmpty().filter { it.size >= 5 && it[1] == "TRANSFORM" && it[4].startsWith("CHANGED") }.groupBy { it[2] }
        changed.filter { it.value.map { event -> event[3] }.distinct().size > 1 }.entries.take(32).forEach {
            findings.add("Multiple transformers changed ${it.key}: ${it.value.map { event -> event[3] }.distinct().joinToString()}. This is a possible interaction, not proof of a conflict.")
        }
        val limitations = mappings.limitations + listOf("Root cause is not automatically proven; ownership and transformation evidence have different strengths.",
            "JAR identity uses size/mtime; identical metadata does not cryptographically prove identical content.")
        val analysis = AnalysisResult(incidentId = report.incidentId, fingerprint = fingerprint,
            rootException = root?.type, suspects = suspects, findings = findings, mappings = mappings.sources, limitations = limitations)
        val budget = ArtifactBudget(directory, maxBytes)
        budget.json("analysis.json", analysis)
        budget.text("deobfuscated-stacktrace.txt") { writer ->
            report.exceptions.nodes.forEach { node ->
                writer.append("#${node.id} ${node.type}: ${node.message.orEmpty()}\n")
                node.frames.forEach { writer.append("  at ${mappings.resolveFrame(it)}\n") }
                writer.append("Cause: ${node.cause}; suppressed: ${node.suppressed}\n\n")
            }
        }
        budget.text("analysis.txt") { writer ->
            writer.append("Crash Tracer — Local Analysis\nFingerprint: $fingerprint\nRoot exception: ${root?.type ?: "unknown"}\n\n")
            if (suspects.isEmpty()) writer.append("Suspected source: unknown\n")
            suspects.forEach { suspect -> writer.append("${suspect.modId}: ${suspect.confidence}\n"); suspect.evidence.forEach { writer.append("  $it\n") } }
            (findings + limitations).forEach { writer.append("- $it\n") }
        }
        return analysis
    }
}
