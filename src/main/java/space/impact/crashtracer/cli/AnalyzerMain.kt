package space.impact.crashtracer.cli

import space.impact.crashtracer.diagnostics.*
import java.io.File

/** Runs without Forge, Minecraft or LaunchWrapper. */
object AnalyzerMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args.isEmpty() || "--help" in args) {
            println("Usage: java -jar crashtracer-analyzer.jar <reports-or-incident-directory> [--mappings <directory>] [--force]")
            return
        }
        val input = File(args[0]).canonicalFile
        require(input.isDirectory) { "Report directory does not exist: $input" }
        val root = if (input.name == "reports") input.parentFile else input.parentFile.parentFile
        val mappingIndex = args.indexOf("--mappings")
        val mappings = if (mappingIndex >= 0) File(args.getOrNull(mappingIndex + 1) ?: error("--mappings requires a directory"))
            else File(root, "mappings/1.7.10")
        val config = (if (File(root, "config.json").isFile) TracerConfig.load(root) else TracerConfig()).copy(maxPendingReports = 4096)
        val groups = PendingAnalysis.process(input, config, mappings, "--force" in args)
        val index = mapOf("schemaVersion" to 1, "groups" to groups)
        if (File(input, "report.json").isFile || File(input, "early.json").isFile) ArtifactBudget(input, config.maxReportBytes).json("analysis-index.json", index)
        else JsonFiles.write(File(input, "analysis-index.json"), index)
        println("Indexed ${groups.values.sumOf { it.size }} incidents in ${groups.size} groups. Results are saved beside each report.")
    }
}
