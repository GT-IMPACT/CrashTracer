package space.impact.crashtracer.diagnostics

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class DiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `shared suppressed exceptions and circular causes preserve identity`() {
        val first = RuntimeException("first")
        val second = IllegalStateException("second")
        first.initCause(second)
        second.initCause(first)
        first.addSuppressed(second)
        val snapshot = ExceptionCollector.collect(first)
        assertEquals(2, snapshot.nodes.size)
        assertEquals(1, snapshot.nodes[0].cause)
        assertEquals(listOf(1), snapshot.nodes[0].suppressed)
        assertEquals(0, snapshot.nodes[1].cause)
        assertNull(snapshot.rootCause)
        assertFalse(snapshot.truncated)
    }

    @Test fun `exception graph limits bound deep chains`() {
        var error: Throwable = RuntimeException("bottom")
        repeat(100) { error = RuntimeException("wrapper", error) }
        val snapshot = ExceptionCollector.collect(error, maxNodes = 8, maxFrames = 2)
        assertEquals(8, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.nodes.all { it.frames.size <= 2 })
        assertNull(snapshot.rootCause)
    }

    @Test fun `wide exception graph obeys character budget without dangling references`() {
        val error = RuntimeException("root".repeat(10000))
        repeat(100) { index ->
            error.addSuppressed(IllegalStateException("suppressed".repeat(1000)).apply {
                stackTrace = Array(300) { StackTraceElement("example.LongClass", "method$index", "LongFile.java", it) }
            })
        }
        val graph = ExceptionCollector.collect(error, maxNodes = 16, maxFrames = 32, maxCharacters = 8192)
        assertTrue(graph.truncated)
        assertEquals(16, graph.nodes.size)
        assertTrue(graph.nodes.sumOf { (it.message?.length ?: 0) + it.frames.sumOf(String::length) } <= 8192)
        val ids = graph.nodes.map { it.id }.toSet()
        assertTrue(graph.nodes.all { node -> node.suppressed.all { it in ids } && (node.cause == null || node.cause in ids) })
    }

    @Test fun `local report round trips and pending analysis never loads suspect classes`() {
        val jar = temporary.newFile("example.jar")
        JarOutputStream(jar.outputStream()).use {
            it.putNextEntry(JarEntry("example/Broken.class"))
            it.write(byteArrayOf(0, 1, 2)) // Deliberately invalid bytecode: only the entry name may be inspected.
            it.closeEntry()
        }
        val error = IllegalStateException("problem")
        error.stackTrace = arrayOf(StackTraceElement("example.Broken", "tick", "Broken.kt", 42))
        val context = ContextSnapshot("INITIALIZATION", listOf(ModSnapshot("example", "Example", "1", jar.path)),
            emptyList(), emptyMap(), emptyList())
        val reports = temporary.newFolder("reports")
        val directory = File(reports, "incident").apply { mkdir() }
        val report = IncidentReport(incidentId = "incident", sessionId = "session", capturedAt = 1,
            source = "test", description = "test report", thread = "main",
            exceptions = ExceptionCollector.collect(error), context = context, suspects = emptyList(), limitations = emptyList())
        LocalReportWriter().write(directory, report)
        val original = File(directory, "report.json").readText()
        assertEquals(0, report.exceptions.rootCause)
        assertTrue(File(directory, "report.txt").readText().contains("IllegalStateException"))
        PendingAnalysis.process(reports)
        val analysis = JsonParser().parse(SupportBundle.readIncident(directory, "analysis.json", 16L * 1024 * 1024)).asJsonObject
        assertEquals("example", analysis.getAsJsonArray("suspects")[0].asJsonObject.get("modId").asString)
        assertEquals(original, SupportBundle.readIncident(directory, "report.json", 16L * 1024 * 1024))
        assertFalse(SupportBundle.readIncident(directory, "stacktrace.txt", 16L * 1024 * 1024).isNullOrEmpty())
    }

    @Test fun `corrupt pending report does not stop processing`() {
        val reports = temporary.newFolder("broken-reports")
        File(reports, "broken").apply { mkdir(); File(this, "report.json").writeText("{") }
        PendingAnalysis.process(reports)
        assertFalse(File(reports, "broken/analysis.json").exists())
    }
}
