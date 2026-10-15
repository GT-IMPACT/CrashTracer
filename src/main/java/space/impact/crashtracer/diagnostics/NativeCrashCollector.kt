package space.impact.crashtracer.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

object NativeCrashCollector {
    /** Import JVM-written files on a later healthy launch; no Java handler can run after a native crash. */
    fun collect(game: File, root: File, context: ContextSnapshot, config: TracerConfig) {
        root.mkdirs()
        val lockFile = File(root, "native-imports.lock")
        if (Files.isSymbolicLink(lockFile.toPath())) return
        RandomAccessFile(lockFile, "rw").use { access ->
            val lock = try { access.channel.tryLock() } catch (_: Exception) { null }
            if (lock == null) return
            lock.use { collectLocked(game, root, context, config) }
        }
    }

    private fun collectLocked(game: File, root: File, context: ContextSnapshot, config: TracerConfig) {
        val manifest = File(root, "native-imports.json")
        val processed = try {
            if (manifest.isFile && manifest.length() < 65536) manifest.reader(Charsets.UTF_8).use {
                JsonFiles.gson.fromJson(it, Array<String>::class.java).toMutableSet()
            } else mutableSetOf()
        } catch (_: Exception) { mutableSetOf<String>() }
        val logs = game.listFiles()?.filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("hs_err_pid[0-9]+\\.log"))
        }?.sortedByDescending { it.lastModified() }?.take(16).orEmpty()
        for (log in logs) {
            val signature = "${log.name}:${log.length()}:${log.lastModified()}"
            if (signature in processed || log.length() > config.maxReportBytes / 4) continue
            try {
                val reports = File(root, "reports").apply { mkdirs() }
                if (Files.isSymbolicLink(reports.toPath()) || reports.toPath().toRealPath().parent != root.toPath().toRealPath()) continue
                val directory = File(reports, "native-${log.name.removeSuffix(".log")}-${log.lastModified()}")
                if (!directory.mkdirs() && !directory.isDirectory) continue
                if (Files.isSymbolicLink(directory.toPath()) || directory.toPath().toRealPath().parent != reports.toPath().toRealPath()) continue
                val incidentLock = File(directory, ".lock")
                if (Files.isSymbolicLink(incidentLock.toPath())) continue
                RandomAccessFile(incidentLock, "rw").use { access ->
                    val lock = try { access.channel.tryLock() } catch (_: Exception) { null }
                    if (lock == null) return@use
                    lock.use {
                        val error = IllegalStateException("Native JVM crash: ${log.name}").apply { stackTrace = emptyArray() }
                        LocalReportWriter(config.maxReportBytes).write(directory, IncidentReport(incidentId = directory.name,
                            sessionId = "native-import", capturedAt = log.lastModified(), source = "jvm-native-log", description = error.message!!,
                            thread = "unknown", exceptions = ExceptionCollector.collect(error), context = context,
                            suspects = emptyList(), limitations = listOf("Imported hs_err from a previous process. Current mod inventory may differ; automatic culprit attribution is unavailable.")))
                        ArtifactBudget(directory, config.maxReportBytes).text("native-${log.name}") { writer ->
                            log.reader(Charsets.UTF_8).use { it.copyTo(writer) }
                        }
                        processed.add(signature)
                    }
                }
            } catch (_: Exception) {}
        }
        JsonFiles.write(manifest, processed.toList().takeLast(256), 65536)
    }
}
