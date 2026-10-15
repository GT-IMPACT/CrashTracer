package space.impact.crashtracer.diagnostics

import java.util.IdentityHashMap

/** Bounded graph traversal preserves shared causes and cycles without recursive serialization. */
object ExceptionCollector {
    fun collect(error: Throwable, maxNodes: Int = 64, maxFrames: Int = 256, maxCharacters: Int = 2 * 1024 * 1024): ExceptionSnapshot {
        require(maxNodes > 0 && maxFrames > 0)
        val ids = IdentityHashMap<Throwable, Int>()
        val queue = ArrayList<Throwable>()
        val nodes = ArrayList<ExceptionNode>()
        var truncated = false
        var remaining = maxCharacters.coerceAtLeast(0)
        fun register(value: Throwable?): Int? {
            if (value == null) return null
            ids[value]?.let { return it }
            if (queue.size >= maxNodes) { truncated = true; return null }
            val id = queue.size
            ids[value] = id
            queue.add(value)
            return id
        }
        register(error)
        var index = 0
        while (index < queue.size) {
            val value = queue[index]
            val frames = value.stackTrace
            if (frames.size > maxFrames || (value.message?.length ?: 0) > 4096) truncated = true
            val cause = register(value.cause)
            val extras = value.suppressed
            if (extras.size > maxNodes) truncated = true
            val suppressed = extras.take(maxNodes).mapNotNull { register(it) }
            val message = value.message?.take(4096.coerceAtMost(remaining))
            remaining -= message?.length ?: 0
            val rendered = mutableListOf<String>()
            for (frame in frames.take(maxFrames)) {
                val line = frame.toString().take(2048)
                if (line.length > remaining) { truncated = true; break }
                rendered.add(line)
                remaining -= line.length
            }
            if ((value.message?.length ?: 0) > (message?.length ?: 0)) truncated = true
            nodes.add(ExceptionNode(index, value.javaClass.name, message, rendered, cause, suppressed))
            index++
        }
        val visited = mutableSetOf<Int>()
        var current = 0
        var rootCause: Int? = null
        while (visited.add(current)) {
            val next = nodes[current].cause
            if (next == null) {
                // A truncated graph cannot prove that this is the end of the original cause chain.
                if (queue[current].cause == null) rootCause = current
                break
            }
            current = next
        }
        return ExceptionSnapshot(0, rootCause, nodes, truncated)
    }
}
