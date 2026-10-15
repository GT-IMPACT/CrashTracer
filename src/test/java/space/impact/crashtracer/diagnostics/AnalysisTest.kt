package space.impact.crashtracer.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnalysisTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `terminated lock owner is a finding without claiming JVM deadlock`() {
        val directory = temporary.newFolder()
        val thread = ThreadSnapshot(2, "Server thread", "WAITING", "ReentrantLock@123", "old Server thread", false,
            emptyList(), 1, false)
        val report = IncidentReport(incidentId = "orphan", sessionId = "session", capturedAt = 1,
            source = "watchdog", description = "stall", thread = "watchdog", exceptions = ExceptionCollector.collect(IllegalStateException()),
            context = ContextSnapshot("TEST", emptyList(), emptyList(), emptyMap(), emptyList()),
            suspects = emptyList(), limitations = emptyList(), threads = listOf(thread))
        val engine = AnalysisEngine(LocalMappings(temporary.newFolder()))
        val result = engine.analyze(directory, report, 1000000)
        assertTrue(result.findings.any { it.contains("no longer live") && it.contains("not proof of a JVM deadlock cycle") })
        assertFalse(result.findings.any { it.startsWith("JVM reported a deadlock") })
        val oldSnapshot = thread.copy(lockOwnerId = null, lockOwnerAlive = null)
        assertTrue(engine.analyze(directory, report.copy(threads = listOf(oldSnapshot)), 1000000).findings.isEmpty())
    }

    @Test fun `local mappings preserve unknown names and ignore CSV descriptions`() {
        val directory = temporary.newFolder()
        File(directory, "methods.csv").writeText("searge,name,side,desc\nfunc_123_a,tick,2,\"quoted, description\"\nfunc_456_b,invalid-name,2,ignored\n")
        File(directory, "fields.csv").writeText("field_123_a,world,2,\"a \"\"quote\"\"\"\n")
        val mappings = LocalMappings(directory)
        assertEquals("example.tick(File.java:42)", mappings.resolveFrame("example.func_123_a(File.java:42)"))
        assertEquals("world", mappings.resolveFrame("field_123_a"))
        assertEquals("func_456_b", mappings.resolveFrame("func_456_b"))
        assertEquals("func_999_z", mappings.resolveFrame("func_999_z"))
        assertEquals(2, mappings.sources.size)
    }

    @Test fun `fingerprint ignores line numbers and exception messages but distinguishes methods`() {
        val engine = AnalysisEngine(LocalMappings(temporary.newFolder()))
        fun analyze(method: String, line: Int, message: String): String {
            val error = IllegalStateException(message).apply { stackTrace = arrayOf(StackTraceElement("example.Test", method, "Test.java", line)) }
            val directory = temporary.newFolder()
            val report = IncidentReport(incidentId = directory.name, sessionId = "session", capturedAt = 1,
                source = "test", description = "test", thread = "main", exceptions = ExceptionCollector.collect(error),
                context = ContextSnapshot("TEST", emptyList(), emptyList(), emptyMap(), emptyList()), suspects = emptyList(), limitations = emptyList())
            return engine.analyze(directory, report, 1000000).fingerprint
        }
        assertEquals(analyze("tick", 1, "one"), analyze("tick", 99, "two"))
        assertNotEquals(analyze("tick", 1, "one"), analyze("render", 1, "one"))
    }

    @Test fun `malformed config is preserved and unsafe limits are bounded`() {
        val root = temporary.newFolder()
        val file = File(root, "config.json").apply { writeText("{bad") }
        assertEquals(TracerConfig(), TracerConfig.load(root))
        assertEquals("{bad", file.readText())
        file.writeText("{\"maxExceptionNodes\":-10,\"maxThreads\":99999,\"maxReportBytes\":1,\"maxBytecodeFiles\":9999}")
        val config = TracerConfig.load(root)
        assertEquals(1, config.maxExceptionNodes)
        assertEquals(512, config.maxThreads)
        assertEquals(1024L * 1024, config.maxReportBytes)
        assertEquals(256, config.maxBytecodeFiles)
    }

    @Test fun `authorization bearer and named credentials are masked together`() {
        val text = SensitiveText.clean("Authorization: Bearer abc.def.secret access_token=token-value password=\"two words\" sessionId=session-value")
        for (secret in listOf("abc.def.secret", "token-value", "two words", "session-value")) assertFalse(text, text.contains(secret))
        assertTrue(text.contains("[redacted]"))
    }

    @Test fun `mixin registry metadata does not imply failure and changed jars lose attribution`() {
        val jar = temporary.newFile("owners.jar")
        java.util.jar.JarOutputStream(jar.outputStream()).use { output ->
            for (entry in listOf("example/Owner.class", "mixins.owner.json")) {
                output.putNextEntry(java.util.jar.JarEntry(entry)); output.write(byteArrayOf(0)); output.closeEntry()
            }
        }
        val error = IllegalStateException().apply { stackTrace = arrayOf(StackTraceElement("example.Owner", "tick", "Owner.java", 1)) }
        val mods = listOf("first", "second").map { ModSnapshot(it, it, "1", jar.path, jar.length(), jar.lastModified()) }
        val report = IncidentReport(incidentId = "owners", sessionId = "session", capturedAt = 1, source = "test", description = "test",
            thread = "main", exceptions = ExceptionCollector.collect(error), context = ContextSnapshot("TEST", mods, emptyList(), emptyMap(), emptyList()),
            suspects = emptyList(), limitations = emptyList())
        fun withStatus(status: String) = report.copy(source = "mixin-error",
            description = "Mixin APPLY failed: example.Owner config=mixins.owner.json target=example.Target",
            forensics = ForensicsSnapshot(listOf(
            MixinRecord("mixins.owner.json", "example.Owner", "example.Target", status, null, null, emptyList())), emptyList(), emptyList()))
        val analyzer = CulpritAnalyzer(mods)
        assertEquals(setOf("first", "second"), analyzer.analyze(report).map { it.modId }.toSet())
        assertTrue(analyzer.analyze(withStatus("REGISTERED")).all { it.confidence == "LOW" })
        assertTrue(analyzer.analyze(withStatus("APPLY_FAILED")).all { it.confidence == "HIGH" })
        assertTrue(analyzer.analyze(withStatus("APPLY_FAILED").copy(source = "minecraft-crash-report", description = "unrelated"))
            .all { it.confidence == "LOW" })
        jar.appendBytes(byteArrayOf(1))
        assertTrue(analyzer.analyze(withStatus("APPLY_FAILED")).isEmpty())
    }

    @Test fun `historical FML and transformer failures do not blame an unrelated incident`() {
        val jar = temporary.newFile("transformer.jar")
        java.util.jar.JarOutputStream(jar.outputStream()).use {
            it.putNextEntry(java.util.jar.JarEntry("example/Transformer.class")); it.write(0); it.closeEntry()
        }
        val mods = listOf(ModSnapshot("owner", "owner", "1", jar.path))
        val report = IncidentReport(incidentId = "history", sessionId = "session", capturedAt = 100,
            source = "minecraft-crash-report", description = "unrelated", thread = "main",
            exceptions = ExceptionCollector.collect(IllegalStateException().apply { stackTrace = emptyArray() }),
            context = ContextSnapshot("TEST", mods, emptyList(), emptyMap(), emptyList()), suspects = emptyList(), limitations = emptyList(),
            forensics = ForensicsSnapshot(emptyList(), listOf(
                listOf("1", "FML_ERROR", "owner", "java.lang.IllegalStateException", "FAILED"),
                listOf("2", "TRANSFORM", "example.Target", "example.Transformer", "FAILED:java.lang.VerifyError")), emptyList()))
        val analyzer = CulpritAnalyzer(mods)
        assertTrue(analyzer.analyze(report).isEmpty())
        assertEquals("MEDIUM", analyzer.analyze(report.copy(source = "fml-lifecycle", description = "FML mod error: owner")).single().confidence)
        assertEquals("MEDIUM", analyzer.analyze(report.copy(source = "asm-transformer", description = "Transformer example.Transformer failed on example.Target")).single().confidence)
    }
}
