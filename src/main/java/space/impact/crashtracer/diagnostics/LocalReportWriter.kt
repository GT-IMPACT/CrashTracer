package space.impact.crashtracer.diagnostics

import java.io.File

class LocalReportWriter(private val maxBytes: Long = 16L * 1024 * 1024) : ReportWriter {
    override fun write(directory: File, report: IncidentReport) {
        val budget = ArtifactBudget(directory, maxBytes)
        budget.json("report.json", report)
        budget.json("mods.json", report.context.mods)
        budget.json("transformers.json", report.context.transformers)
        budget.json("mixins.json", report.forensics ?: ForensicsSnapshot(emptyList(), emptyList(), listOf("Unavailable")))
        budget.json("memory.json", report.memory)
        report.interaction?.let {
            budget.json("interaction.json", it)
            budget.text("interaction.txt") { writer -> writer.write(InteractionDetails.text(it)) }
        }
        budget.text("threads.txt") { writer ->
            report.threads.forEach { thread ->
                writer.append("\"${thread.name}\" id=${thread.id} ${thread.state} deadlocked=${thread.deadlocked}\n")
                writer.append("Lock: ${thread.lock}; owner: ${thread.lockOwner}; ownerId: ${thread.lockOwnerId}; ownerAlive: ${thread.lockOwnerAlive}\n")
                thread.frames.forEach { writer.append("  at $it\n") }
                writer.append("\n")
            }
        }
        budget.text("stacktrace.txt") { writer ->
            for (node in report.exceptions.nodes) {
                writer.append("#${node.id} ${node.type}: ${node.message.orEmpty()}\n")
                node.frames.forEach { writer.append("  at $it\n") }
                writer.append("Cause: ${node.cause}; suppressed: ${node.suppressed}\n\n")
            }
        }
        budget.text("report.txt") { writer ->
            writer.append("Crash Tracer — Diagnostic Report\n")
            writer.append("Incident: ${report.incidentId}\nSession: ${report.sessionId}\n")
            writer.append("Phase: ${report.context.phase}\nSource: ${report.source}\n")
            writer.append("Description: ${report.description}\nThread: ${report.thread}\n")
            if (report.interaction != null) writer.append("Player interaction: see interaction.txt / interaction.json (before processing and at failure).\n")
            val root = report.exceptions.nodes.first()
            writer.append("Exception: ${root.type}: ${root.message.orEmpty()}\n\n")
            val cause = report.exceptions.rootCause?.let { report.exceptions.nodes[it] }
            writer.append("Root exception: ${cause?.type ?: "Unknown (cyclic or incomplete cause chain)"}\n")
            writer.append("Root cause: not established\n\n")
            writer.append("Post-capture analysis: see analysis.txt and analysis.json when available.\n\n")
            writer.append("Suspected sources (stack frame ownership is not proof of cause):\n")
            if (report.suspects.isEmpty()) writer.append("Unknown\n")
            report.suspects.forEach { suspect ->
                writer.append("${suspect.modId} — confidence ${suspect.confidence}\n")
                suspect.evidence.forEach { writer.append("  $it\n") }
            }
            writer.append("\nLimitations:\n")
            (report.limitations + report.context.limitations).forEach { writer.append("- $it\n") }
            writer.append("\nSee report.json and stacktrace.txt for the exception graph and context.\n")
        }
    }

}
