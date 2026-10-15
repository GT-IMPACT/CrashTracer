package space.impact.crashtracer.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

data class BundleStatus(val summary: PlayerSummary, val archive: File? = null, val failed: Boolean = false)

/** One replaceable archive per incident, independent of Minecraft and usable by the CLI. */
object SupportBundle {
    private val states = ConcurrentHashMap<String, BundleStatus>()
    private val reportFiles = setOf("report.json", "report.txt", "early.json", "emergency.txt", "analysis.json", "analysis.txt",
        "stacktrace.txt", "deobfuscated-stacktrace.txt", "mods.json", "mixins.json", "runtime-mixins.json", "transformers.json",
        "threads.txt", "memory.json", "sources.jsonl", "collector-failure.txt", "interaction.json", "interaction.txt", "minecraft-report-name.txt", "analysis-index.json")
    fun archive(directory: File) = File(File(directory, "share"), "crashtracer-${directory.name.take(120)}.zip")
    fun state(directory: File): BundleStatus? = states[directory.absolutePath]
    fun pending(directory: File, report: IncidentReport) {
        states.putIfAbsent(directory.absolutePath, BundleStatus(PlayerSummary.create(report, null)))
    }
    fun isArchive(name: String) = name.startsWith("crashtracer-") && (name.endsWith(".zip") || name.endsWith(".zip.partial"))
    fun hasReport(directory: File) = File(directory, "report.json").isFile || File(directory, "early.json").isFile || archive(directory).isFile
    private fun owned(name: String) = name in reportFiles || name.matches(Regex("native-hs_err_pid[0-9]+\\.log"))
    private fun hasRaw(directory: File) = directory.listFiles()?.any { owned(it.name) || it.name == "summary.txt" } == true
    fun needsPacking(directory: File) = !archive(directory).isFile || hasRaw(directory)

    /** Reads archive-only incidents without unpacking or recreating their original files. */
    fun readIncident(directory: File, name: String, limit: Long): String? {
        require(owned(name))
        val raw = File(directory, name)
        if (raw.exists()) {
            if (!raw.isFile || Files.isSymbolicLink(raw.toPath()) || raw.length() > limit) return null
            return raw.readText(Charsets.UTF_8)
        }
        val bundle = archive(directory)
        if (!bundle.isFile || bundle.toPath().toRealPath() != bundle.toPath().toAbsolutePath().normalize()) return null
        return ZipFile(bundle).use { zip -> zip.getEntry("incident/$name")?.let { entry ->
            String(readEntry(zip, entry, limit), Charsets.UTF_8)
        } }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        require(entry.size in 0..limit)
        val crc = CRC32()
        val data = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(data.size().toLong() + count <= limit)
                crc.update(buffer, 0, count); data.write(buffer, 0, count)
            }
        }
        require(data.size().toLong() == entry.size && crc.value == entry.crc) { "ZIP entry verification failed: ${entry.name}" }
        return data.toByteArray()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

    /** Full decompression, CRC and content checks before any incident originals may be removed. */
    internal fun verify(file: File, limit: Long, expected: Map<String, List<Byte>>? = null) {
        require(!Files.isSymbolicLink(file.toPath()))
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 3..128 && entries.map { it.name }.distinct().size == entries.size)
            require(entries.any { it.name == "READ-ME-FIRST.txt" } && entries.any { it.name == "CONTENTS.txt" } && entries.any { it.name == "incident/report.json" })
            var remaining = limit
            for (entry in entries) {
                require(!entry.isDirectory && !entry.name.startsWith("/") && entry.name.split('/').none { it == ".." || it == "." })
                val bytes = readEntry(zip, entry, remaining)
                remaining -= bytes.size
                if (expected != null) require(expected[entry.name] == digest(bytes)) { "ZIP content mismatch" }
            }
            if (expected != null) require(expected.keys == entries.map { it.name }.toSet())
        }
    }

    /** Called with the incident's file lock held. Failure never damages an existing archive. */
    fun write(directory: File, report: IncidentReport, analysis: AnalysisResult?, config: TracerConfig) {
        val summary = PlayerSummary.create(report, analysis)
        val target = archive(directory)
        val temporary = File(target.parentFile, target.name + ".partial")
        try {
            val realDirectory = directory.toPath().toRealPath()
            require(directory.toPath().toAbsolutePath().normalize() == realDirectory) { "Linked incident directory" }
            if (!target.parentFile.exists()) Files.createDirectory(target.parentFile.toPath())
            require(target.parentFile.toPath().toRealPath() == realDirectory.resolve("share")) { "Linked share directory" }
            require(!Files.isSymbolicLink(target.toPath()) && !Files.isSymbolicLink(temporary.toPath()))
            val limit = config.maxReportBytes + 4L * 1024 * 1024
            var remaining = limit
            val included = mutableListOf<String>()
            val omitted = mutableListOf<String>()
            val expected = linkedMapOf<String, List<Byte>>()
            val originals = linkedMapOf<File, List<Byte>>()
            val previous = if (target.isFile) {
                verify(target, limit)
                ZipFile(target)
            } else null
            try {
            temporary.outputStream().buffered().use { raw -> ZipOutputStream(raw).use { zip ->
                fun add(name: String, data: ByteArray): Boolean {
                    if (data.size > remaining - 8192) { omitted.add("$name: bundle size limit"); return false }
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                    remaining -= data.size; included.add(name)
                    expected[name] = digest(data)
                    return true
                }
                fun addFile(name: String, file: File, max: Int, tail: Boolean = false) {
                    if (!file.isFile) { omitted.add("$name: unavailable"); return }
                    if (Files.isSymbolicLink(file.toPath()) || file.toPath().toRealPath().parent != file.parentFile.toPath().toAbsolutePath().normalize()) {
                        omitted.add("$name: linked path skipped"); return
                    }
                    if (!tail && file.length() > max) { omitted.add("$name: file size limit"); return }
                    val bytes = RandomAccessFile(file, "r").use {
                        val size = minOf(it.length(), max.toLong()).toInt()
                        if (tail && it.length() > size) { it.seek(it.length() - size); omitted.add("$name: only last $size bytes included") }
                        ByteArray(size).also { bytes -> it.readFully(bytes) }
                    }
                    val sanitized = SensitiveText.clean(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    if (tail && sanitized.size > max) {
                        var start = sanitized.size - max
                        while (start < sanitized.size && (sanitized[start].toInt() and 0xC0) == 0x80) start++
                        add(name, sanitized.copyOfRange(start, sanitized.size))
                    } else {
                        val content = if (name == "incident/sources.jsonl" && previous?.getEntry(name) != null)
                            readEntry(previous, previous.getEntry(name), config.maxReportBytes) + sanitized else sanitized
                        if (add(name, content) && file.parentFile == directory) originals[file] = digest(bytes)
                    }
                }
                add("READ-ME-FIRST.txt", summary.text().toByteArray(Charsets.UTF_8))
                directory.listFiles()?.filter { owned(it.name) }
                    ?.sortedBy { it.name }?.forEach { addFile("incident/${it.name}", it, config.maxReportBytes.toInt()) }
                // The report/analysis models are available even when no raw files remain.
                if ("incident/report.json" !in expected) {
                    val saved = previous?.getEntry("incident/report.json")
                    add("incident/report.json", if (saved != null) readEntry(previous, saved, config.maxReportBytes)
                        else JsonFiles.gson.toJson(report).toByteArray(Charsets.UTF_8))
                }
                if (analysis != null && "incident/analysis.json" !in expected) add("incident/analysis.json", JsonFiles.gson.toJson(analysis).toByteArray(Charsets.UTF_8))
                val root = directory.parentFile.parentFile
                val validRoot = directory.parentFile.name == "reports" && directory.parentFile.toPath().toRealPath() == root.toPath().toRealPath().resolve("reports")
                if (validRoot && report.sessionId.matches(Regex("session-[a-zA-Z0-9-]+"))) {
                    val session = File(root, "sessions/${report.sessionId}")
                    val expected = root.toPath().toRealPath().resolve("sessions").resolve(report.sessionId)
                    if (session.isDirectory && session.toPath().toRealPath() == expected) {
                        for (name in listOf("session.json", "context.json", "closed.json")) addFile("session/$name", File(session, name), 1024 * 1024)
                        addFile("session/events.jsonl", File(session, "events.jsonl"), 1024 * 1024, true)
                    }
                }
                if (validRoot) {
                    val game = root.parentFile
                    val expectedLogs = game.toPath().toRealPath().resolve("logs")
                    val logs = File(game, "logs")
                    // Never attach a later JVM's logs while recovering an old incident.
                    val sessions = File(root, "sessions")
                    val latestSession = if (sessions.isDirectory && sessions.toPath().toRealPath() == root.toPath().toRealPath().resolve("sessions"))
                        sessions.listFiles()?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && File(it, "session.json").isFile }
                            ?.maxByOrNull { File(it, "session.json").lastModified() }?.name else null
                    val sessionMetadata = File(sessions, "${report.sessionId}/session.json")
                    val startedAt = if (latestSession == report.sessionId && sessionMetadata.isFile && !Files.isSymbolicLink(sessionMetadata.toPath()))
                        sessionMetadata.lastModified() else Long.MAX_VALUE
                    for (name in listOf("latest.log", "fml-client-latest.log", "fml-server-latest.log")) {
                        val file = File(logs, name)
                        if (latestSession == report.sessionId && logs.isDirectory && logs.toPath().toRealPath() == expectedLogs && file.isFile &&
                            file.lastModified() >= startedAt && file.lastModified() <= report.capturedAt + 120000)
                            addFile("logs/$name", file, 1024 * 1024, true)
                        else omitted.add("logs/$name: unavailable or outside incident time window")
                    }
                    val link = readIncident(directory, "minecraft-report-name.txt", 256)
                    if (link != null) {
                        val name = link.trim()
                        if (name.matches(Regex("[a-zA-Z0-9_.-]+\\.txt"))) {
                            val folder = File(game, "crash-reports")
                            if (folder.isDirectory && folder.toPath().toRealPath() == game.toPath().toRealPath().resolve("crash-reports"))
                                addFile("minecraft/$name", File(folder, name), 2 * 1024 * 1024)
                        }
                    }
                }
                previous?.entries()?.asSequence()?.forEach { entry ->
                    val name = entry.name
                    val allowed = name.startsWith("incident/") && owned(name.removePrefix("incident/")) ||
                        name in setOf("session/session.json", "session/context.json", "session/closed.json", "session/events.jsonl",
                            "logs/latest.log", "logs/fml-client-latest.log", "logs/fml-server-latest.log") ||
                        name.matches(Regex("minecraft/[a-zA-Z0-9_.-]+\\.txt"))
                    require(allowed || name in setOf("READ-ME-FIRST.txt", "CONTENTS.txt")) { "Unknown archived content: $name" }
                    if (allowed && name !in expected) add(name, readEntry(previous, entry, limit))
                }
                val manifest = "Incident: ${report.incidentId}\nSession: ${report.sessionId}\nCaptured at (epoch ms): ${report.capturedAt}\n" +
                    "Included:\n${included.joinToString("\n")}\n\nUnavailable / limited:\n${omitted.joinToString("\n")}\n" +
                    "Logs are bounded snapshots taken during packaging. No mods, saves, configs or unrelated reports are included.\n"
                val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
                require(manifestBytes.size <= remaining)
                zip.putNextEntry(ZipEntry("CONTENTS.txt")); zip.write(manifestBytes); zip.closeEntry()
                expected["CONTENTS.txt"] = digest(manifestBytes)
            } }
            } finally { previous?.close() }
            verify(temporary, limit, expected)
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            verify(target, limit, expected)
            states[directory.absolutePath] = BundleStatus(summary, target)
            // Never delete a skipped, linked, oversized or concurrently changed original.
            val rawFiles = directory.listFiles()?.filter { owned(it.name) }.orEmpty()
            val complete = rawFiles.all { file ->
                !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= config.maxReportBytes &&
                    originals[file] == digest(file.readBytes())
            }
            if (complete) {
                for (file in rawFiles) if (file.toPath().toRealPath().parent == realDirectory &&
                    !Files.isSymbolicLink(file.toPath()) && originals[file] == digest(file.readBytes())) Files.deleteIfExists(file.toPath())
                val duplicateSummary = File(directory, "summary.txt")
                if (duplicateSummary.isFile && !Files.isSymbolicLink(duplicateSummary.toPath()) &&
                    duplicateSummary.toPath().toRealPath().parent == realDirectory) Files.deleteIfExists(duplicateSummary.toPath())
            }
        } catch (failure: Exception) {
            val usable = try { verify(target, config.maxReportBytes + 4L * 1024 * 1024); target } catch (_: Exception) { null }
            states[directory.absolutePath] = BundleStatus(summary, usable, true)
            throw failure
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    fun refresh(directory: File, config: TracerConfig) {
        val lockFile = File(directory, ".lock")
        if (Files.isSymbolicLink(lockFile.toPath())) return
        RandomAccessFile(lockFile, "rw").use { access ->
            val lock = try { access.channel.tryLock() } catch (_: Exception) { null } ?: return
            lock.use {
                val report = PendingAnalysis.readReport(File(directory, "report.json"), config.maxReportBytes) ?: return
                val analysis = readIncident(directory, "analysis.json", config.maxReportBytes)?.let { JsonFiles.gson.fromJson(it, AnalysisResult::class.java) }
                if (archive(directory).isFile && !hasRaw(directory)) {
                    states[directory.absolutePath] = BundleStatus(PlayerSummary.create(report, analysis), archive(directory))
                    return
                }
                write(directory, report, analysis, config)
            }
        }
    }
}
