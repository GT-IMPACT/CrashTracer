package space.impact.crashtracer.diagnostics

import space.impact.crashtracer.bootstrap.Heartbeats
import space.impact.crashtracer.bootstrap.TracerBootstrap
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Watchdog(private val config: TracerConfig) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CrashTracer-watchdog").apply { isDaemon = true }
    }
    private var lastReport = 0L

    fun start() {
        if (config.watchdog) executor.scheduleWithFixedDelay({ inspect() }, 10, 5, TimeUnit.SECONDS)
    }

    private fun inspect() {
        try {
            val now = System.nanoTime()
            if (lastReport != 0L && now - lastReport < TimeUnit.SECONDS.toNanos(config.watchdogCooldownSeconds.toLong())) return
            val stalled = Heartbeats.snapshot().filter {
                it.active && it.thread.isAlive && now - it.startedAt > TimeUnit.SECONDS.toNanos(config.hangThresholdSeconds.toLong())
            }
            val bean = ManagementFactory.getThreadMXBean()
            val deadlocks = try { bean.findDeadlockedThreads() } catch (_: UnsupportedOperationException) { bean.findMonitorDeadlockedThreads() }
            val hasDeadlock = deadlocks != null && deadlocks.isNotEmpty()
            if (stalled.isEmpty() && !hasDeadlock) return
            val error = IllegalStateException(if (hasDeadlock) "JVM deadlock detected" else "Game tick exceeded watchdog threshold")
            stalled.firstOrNull()?.let { error.stackTrace = it.thread.stackTrace }
            lastReport = now
            TracerBootstrap.event("WATCHDOG", stalled.joinToString { it.thread.name }, "", if (hasDeadlock) "DEADLOCK" else "STALL")
            TracerBootstrap.capture(error.message, error, "watchdog")
        } catch (_: Throwable) {}
    }

    override fun close() { executor.shutdownNow() }
}
