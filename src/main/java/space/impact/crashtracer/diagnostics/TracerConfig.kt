package space.impact.crashtracer.diagnostics

import com.google.gson.Gson
import java.io.File

data class TracerConfig(
    val schemaVersion: Int = 1,
    val enabled: Boolean = true,
    val maxIncidentsPerSession: Int = 32,
    val maxExceptionNodes: Int = 64,
    val maxFramesPerException: Int = 256,
    val eventCapacity: Int = 512,
    val maxEventsOnDisk: Int = 10000,
    val maxReportBytes: Long = 16L * 1024 * 1024,
    val maxReports: Int = 128,
    val maxSessions: Int = 16,
    val cleanup: Boolean = true,
    val threadDump: Boolean = true,
    val logCapture: Boolean = true,
    val maxThreads: Int = 128,
    val watchdog: Boolean = true,
    val hangThresholdSeconds: Int = 60,
    val watchdogCooldownSeconds: Int = 300,
    val experimentalTransformers: Boolean = false,
    val bytecodeExport: Boolean = false,
    val maxBytecodeFiles: Int = 64,
    val maxBytecodeBytes: Int = 4 * 1024 * 1024,
    val analyzeOnStartup: Boolean = true,
    val maxPendingReports: Int = 32,
) {
    fun bounded() = copy(
        maxIncidentsPerSession = maxIncidentsPerSession.coerceIn(1, 256),
        maxExceptionNodes = maxExceptionNodes.coerceIn(1, 128),
        maxFramesPerException = maxFramesPerException.coerceIn(1, 512),
        eventCapacity = eventCapacity.coerceIn(16, 8192),
        maxEventsOnDisk = maxEventsOnDisk.coerceIn(0, 100000),
        maxReportBytes = maxReportBytes.coerceIn(1024 * 1024, 64L * 1024 * 1024),
        maxReports = maxReports.coerceIn(1, 4096), maxSessions = maxSessions.coerceIn(1, 256),
        maxThreads = maxThreads.coerceIn(1, 512), hangThresholdSeconds = hangThresholdSeconds.coerceIn(10, 3600),
        watchdogCooldownSeconds = watchdogCooldownSeconds.coerceIn(30, 86400),
        maxBytecodeFiles = maxBytecodeFiles.coerceIn(0, 256),
        maxBytecodeBytes = maxBytecodeBytes.coerceIn(1024, 16 * 1024 * 1024),
        maxPendingReports = maxPendingReports.coerceIn(1, 128),
    )

    companion object {
        fun load(root: File): TracerConfig {
            val file = File(root, "config.json")
            val value = try {
                if (file.isFile && file.length() <= 65536) file.reader(Charsets.UTF_8).use {
                    Gson().fromJson(it, TracerConfig::class.java).also { config ->
                        require(config != null && config.schemaVersion == 1) { "Unsupported configuration" }
                    }
                } else TracerConfig()
            } catch (error: Exception) {
                System.err.println("Crash Tracer: invalid config.json; using defaults (${error.javaClass.simpleName}).")
                TracerConfig()
            }
            if (!file.exists()) JsonFiles.write(file, value, 65536)
            return value.bounded()
        }
    }
}
