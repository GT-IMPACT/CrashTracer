package space.impact.crashtracer.diagnostics

import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

data class EarlyIncident(val schemaVersion: Int, val sessionId: String, val capturedAt: Long,
    val source: String, val description: String, val phase: String, val thread: String, val exceptions: ExceptionSnapshot)

object PendingAnalysis {
    fun process(reports: File, config: TracerConfig = TracerConfig(), mappingsDirectory: File = File(reports.parentFile, "mappings/1.7.10"),
        force: Boolean = false): Map<String, List<String>> {
        val groups = linkedMapOf<String, MutableList<String>>()
        val engine = AnalysisEngine(LocalMappings(mappingsDirectory))
        val directories = if (SupportBundle.hasReport(reports)) listOf(reports)
            else reports.listFiles()?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                ?.sortedByDescending { it.name }.orEmpty()
        var attempts = 0
        for (directory in directories) {
            try {
                val existing = if (!force) SupportBundle.readIncident(directory, "analysis.json", config.maxReportBytes) else null
                if (existing != null) {
                    val fingerprint = try {
                        JsonParser().parse(existing).asJsonObject.get("fingerprint")?.asString
                    } catch (_: Exception) { null }
                    if (fingerprint != null) {
                        groups.getOrPut(fingerprint) { mutableListOf() }.add(directory.name)
                        if (SupportBundle.needsPacking(directory) && attempts < config.maxPendingReports) {
                            attempts++
                            try { SupportBundle.refresh(directory, config) } catch (_: Exception) { }
                        }
                        continue
                    }
                }
                if (attempts >= config.maxPendingReports) continue
                attempts++
                val lockPath = File(directory, ".lock")
                if (Files.isSymbolicLink(lockPath.toPath())) continue
                RandomAccessFile(lockPath, "rw").use accessBlock@ { access ->
                    val lock = try { access.channel.tryLock() } catch (_: Exception) { null }
                    if (lock == null) return@accessBlock
                    lock.use {
                        var report = try { readReport(File(directory, "report.json"), config.maxReportBytes) }
                        catch (unsupported: UnsupportedOperationException) { throw unsupported }
                        catch (invalid: Exception) {
                            // A complete early capture survives a truncated/corrupt full collector output.
                            if (!File(directory, "early.json").isFile) throw invalid
                            null
                        }
                        if (report == null) {
                            val earlyFile = File(directory, "early.json")
                            if (!earlyFile.isFile || earlyFile.length() > config.maxReportBytes) return@accessBlock
                            val early = earlyFile.reader(Charsets.UTF_8).use { JsonFiles.gson.fromJson(it, EarlyIncident::class.java) }
                            require(early.schemaVersion == 1)
                            val session = File(directory.parentFile.parentFile, "sessions/${early.sessionId}")
                            val contextFile = File(session, "context.json")
                            val context = if (early.sessionId.matches(Regex("session-[a-zA-Z0-9-]+")) && contextFile.isFile && contextFile.length() < config.maxReportBytes) {
                                contextFile.reader(Charsets.UTF_8).use { JsonFiles.gson.fromJson(it, ContextSnapshot::class.java) }
                                    .copy(phase = early.phase)
                            } else ContextSnapshot(early.phase, emptyList(), emptyList(), emptyMap(), listOf("Runtime context unavailable at capture time."))
                            report = IncidentReport(incidentId = directory.name, sessionId = early.sessionId,
                                capturedAt = early.capturedAt, source = early.source, description = early.description,
                                thread = early.thread, exceptions = early.exceptions, context = context,
                                suspects = emptyList(), limitations = listOf("Recovered from early capture; context may be from a later phase of the same session."),
                                forensics = if (early.sessionId.matches(Regex("session-[a-zA-Z0-9-]+"))) recoverEvents(session, early.capturedAt, config) else null)
                            LocalReportWriter(config.maxReportBytes).write(directory, report)
                        }
                        val mixinData = SupportBundle.readIncident(directory, "runtime-mixins.json", config.maxReportBytes)
                        if (mixinData != null) {
                            val extra = JsonFiles.gson.fromJson(mixinData, ForensicsSnapshot::class.java)
                            val previous = report.forensics
                            report = report.copy(forensics = ForensicsSnapshot((previous?.mixins.orEmpty() + extra.mixins).distinct(),
                                previous?.events.orEmpty(), (previous?.limitations.orEmpty() + extra.limitations).distinct()))
                        }
                        val result = engine.analyze(directory, report, config.maxReportBytes)
                        groups.getOrPut(result.fingerprint) { mutableListOf() }.add(directory.name)
                        try { SupportBundle.write(directory, report, result, config) }
                        catch (failure: Exception) { System.err.println("Crash Tracer: support archive unavailable for ${directory.name}: ${failure.javaClass.simpleName}") }
                    }
                }
            } catch (error: Exception) {
                System.err.println("Crash Tracer: analysis skipped for ${directory.name}: ${error.javaClass.simpleName}")
            }
        }
        return groups
    }

    private fun recoverEvents(session: File, capturedAt: Long, config: TracerConfig): ForensicsSnapshot {
        val file = File(session, "events.jsonl")
        val events = java.util.ArrayDeque<List<String>>()
        if (file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= 128L * 1024 * 1024) {
            file.useLines(Charsets.UTF_8) { lines -> lines.take(config.maxEventsOnDisk).forEach { line ->
                try {
                    val value = JsonParser().parse(line)
                    val event = if (value.isJsonArray) value.asJsonArray.map { it.asString }
                        else listOf("timestamp", "kind", "target", "detail", "status").map { value.asJsonObject.get(it)?.asString.orEmpty() }
                    if (event.size == 5 && (event[0].toLongOrNull() ?: Long.MAX_VALUE) <= capturedAt) {
                        while (events.size >= config.eventCapacity) events.removeFirst()
                        events.addLast(event)
                    }
                } catch (_: Exception) {}
            } }
        }
        val records = events.filter { it[1] == "MIXIN" }.map {
            MixinRecord(it[3].substringAfter("config=", "unknown"), it[3].substringBefore(" config="), it[2], it[4], null, null, emptyList())
        }
        return ForensicsSnapshot(records, events.toList(), listOf("Recovered metadata from bounded session events."))
    }

    fun readReport(file: File, limit: Long): IncidentReport? {
        val data = SupportBundle.readIncident(file.parentFile, file.name, limit) ?: return null
        val json = JsonParser().parse(data).asJsonObject
        if (json.get("schemaVersion")?.asInt != 1) throw UnsupportedOperationException("Unsupported report schema")
        if (!json.has("threads")) json.add("threads", com.google.gson.JsonArray())
        if (!json.has("memory")) json.add("memory", com.google.gson.JsonObject())
        val context = json.getAsJsonObject("context")
        for (name in listOf("coremods", "tweakers")) if (!context.has(name)) context.add(name, com.google.gson.JsonArray())
        return JsonFiles.gson.fromJson(json, IncidentReport::class.java).also { report ->
            require(report.exceptions.nodes.size in 1..128)
            val ids = report.exceptions.nodes.map { it.id }.toSet()
            require(ids.size == report.exceptions.nodes.size && report.exceptions.root in ids)
            require(report.exceptions.nodes.withIndex().all { it.index == it.value.id })
            require(report.exceptions.rootCause == null || report.exceptions.rootCause in ids)
            require(report.exceptions.nodes.all { it.cause == null || it.cause in ids })
            require(report.exceptions.nodes.all { node -> node.suppressed.all { it in ids } && node.frames.size <= 512 })
        }
    }
}
