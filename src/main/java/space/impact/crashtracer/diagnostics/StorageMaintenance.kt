package space.impact.crashtracer.diagnostics

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Comparator

object StorageMaintenance {
    fun clean(root: File, currentSession: File, config: TracerConfig) {
        if (!config.cleanup) return
        retain(File(root, "reports"), config.maxReports, null)
        retain(File(root, "sessions"), config.maxSessions, currentSession)
    }

    private fun retain(parent: File, limit: Int, protected: File?) {
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) return
        val root = parent.toPath().toRealPath()
        val candidates = parent.listFiles()?.filter {
            it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                it.name.matches(Regex("(?:session-)?[a-zA-Z0-9_-]+")) &&
                (if (parent.name == "sessions") File(it, "session.json").isFile
                    else File(it, "report.json").isFile || File(it, "early.json").isFile || File(it, "emergency.txt").isFile)
        }?.sortedByDescending { it.lastModified() } ?: return
        val kept = candidates.take(limit).toSet() + listOfNotNull(protected)
        for (directory in candidates.filter { it !in kept }) {
            try {
                val path = directory.toPath().toRealPath()
                if (path.parent != root || path == root) continue
                val lockFile = File(directory, ".lock")
                if (Files.isSymbolicLink(lockFile.toPath())) continue
                var acquired = false
                RandomAccessFile(lockFile, "rw").use { access ->
                    val lock = try { access.channel.tryLock() } catch (_: Exception) { null }
                    if (lock == null) return@use
                    acquired = true
                    lock.use {
                        // No FOLLOW_LINKS: links inside a report are removed, never traversed.
                        Files.walk(path).use { paths ->
                            paths.sorted(Comparator.reverseOrder()).filter { it != lockFile.toPath() && it != path }
                                .forEach {
                                    if (Files.isSymbolicLink(it) || it.toRealPath().startsWith(path)) Files.deleteIfExists(it)
                                }
                        }
                    }
                }
                if (!acquired) continue
                Files.deleteIfExists(lockFile.toPath())
                Files.deleteIfExists(path)
            } catch (_: Exception) {
                // Another process, permissions or an incomplete report may prevent cleanup.
            }
        }
    }
}
