package space.impact.crashtracer.diagnostics

import java.lang.management.ManagementFactory

object ThreadCollector {
    fun collect(limit: Int, preferredThreadIds: List<Long> = emptyList()): List<ThreadSnapshot> {
        val bean = ManagementFactory.getThreadMXBean()
        val deadlocked = try { bean.findDeadlockedThreads()?.toSet().orEmpty() }
            catch (_: UnsupportedOperationException) { bean.findMonitorDeadlockedThreads()?.toSet().orEmpty() }
        // MXBean order is unspecified. A large modpack can otherwise evict even the
        // capturing client thread. Deadlocks keep priority over the normal snapshot.
        val priority = listOf(Thread.currentThread().id) + preferredThreadIds
        val ids = (deadlocked.toList() + priority + bean.allThreadIds.toList()).distinct().take(limit).toLongArray()
        return bean.getThreadInfo(ids, 128).filterNotNull().map {
            val owner = it.lockOwnerId.takeIf { id -> id >= 0 }
            ThreadSnapshot(it.threadId, it.threadName.take(256), it.threadState.name,
                it.lockName, it.lockOwnerName, it.threadId in deadlocked, it.stackTrace.map { frame -> frame.toString() },
                owner, owner?.let { id -> bean.getThreadInfo(id) != null })
        }
    }
}
