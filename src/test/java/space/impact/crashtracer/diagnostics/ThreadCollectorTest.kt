package space.impact.crashtracer.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThreadCollectorTest {
    @Test fun `snapshot distinguishes a live lock owner from a terminated owner`() {
        val lock = java.util.concurrent.locks.ReentrantLock()
        val acquired = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val owner = Thread({
            lock.lock()
            acquired.countDown()
            finish.await() // Intentionally exits without unlocking, as a failed mod tick can.
        }, "Orphan-lock-owner").apply { isDaemon = true; start() }
        val waiter = Thread({
            try { lock.lockInterruptibly(); lock.unlock() }
            catch (_: InterruptedException) { }
        }, "Orphan-lock-waiter").apply { isDaemon = true }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            waiter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiter.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, waiter.state)
            fun snapshot() = ThreadCollector.collect(3, listOf(waiter.id, owner.id)).first { it.id == waiter.id }
            val live = snapshot()
            assertEquals(owner.id, live.lockOwnerId)
            assertEquals(true, live.lockOwnerAlive)
            finish.countDown()
            owner.join(5000)
            assertFalse(owner.isAlive)
            val orphan = snapshot()
            assertEquals(owner.id, orphan.lockOwnerId)
            assertEquals(false, orphan.lockOwnerAlive)
            assertFalse(orphan.deadlocked)
        } finally {
            finish.countDown()
            waiter.interrupt()
            waiter.join(5000)
            owner.join(5000)
        }
    }

    @Test fun `limited snapshot retains capturing and preferred game threads`() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val game = Thread({
            ready.countDown()
            release.await()
        }, "Snapshot-preferred-game-thread").apply { isDaemon = true; start() }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val snapshot = ThreadCollector.collect(2, listOf(game.id, game.id))
            assertEquals(2, snapshot.size)
            assertEquals(setOf(Thread.currentThread().id, game.id), snapshot.map { it.id }.toSet())
        } finally {
            release.countDown()
            game.join(5000)
        }
    }
}
