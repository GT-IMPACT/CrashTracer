package space.impact.crashtracer.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class SupportBundleTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report(id: String = "incident", session: String = "session-test", error: Throwable = IllegalStateException("probe")) =
        IncidentReport(incidentId = id, sessionId = session, capturedAt = System.currentTimeMillis(), source = "minecraft-crash-report",
            description = "Unexpected error", thread = "Client thread", exceptions = ExceptionCollector.collect(error),
            context = ContextSnapshot("SERVER_STARTED", listOf(ModSnapshot("testmod", "Test Mod", "1", null)), emptyList(), emptyMap(), emptyList()),
            suspects = emptyList(), limitations = emptyList())

    @Test fun `one self contained ZIP includes summary incident session linked report and masked bounded logs`() {
        val game = temporary.newFolder("game")
        val root = File(game, "crash-tracer").apply { mkdirs() }
        val directory = File(root, "reports/incident").apply { mkdirs() }
        val session = File(root, "sessions/session-test").apply { mkdirs() }
        File(session, "session.json").writeText("{\"sessionId\":\"session-test\"}")
        File(session, "context.json").writeText("{}")
        File(session, "events.jsonl").writeText("{\"event\":\"test\"}\n")
        File(game, "logs").mkdirs()
        File(game, "logs/latest.log").writeText("prefix\n".repeat(200000) + "Authorization: Bearer SECRET password=PASSWORD\n")
        File(game, "logs/fml-server-latest.log").apply { writeText("OLD-JVM-SERVER-LOG"); setLastModified(1000) }
        File(game, "crash-reports").mkdirs()
        File(game, "crash-reports/own.txt").writeText("linked vanilla report")
        File(game, "crash-reports/unrelated.txt").writeText("DO-NOT-INCLUDE")
        File(directory, "minecraft-report-name.txt").writeText("own.txt")
        File(directory, "unrelated-secret.txt").writeText("DO-NOT-INCLUDE")
        val report = report()
        LocalReportWriter().write(directory, report)
        val analysis = AnalysisResult(incidentId = "incident", fingerprint = "hash", rootException = "IllegalStateException",
            suspects = listOf(Suspect("testmod", "LOW", listOf("frame ownership"))), findings = emptyList(), mappings = emptyList(), limitations = emptyList())
        SupportBundle.write(directory, report, analysis, TracerConfig())
        val archive = SupportBundle.archive(directory)
        assertTrue(archive.isFile)
        assertFalse(File(directory, "report.json").exists())
        assertFalse(File(directory, "report.txt").exists())
        assertTrue(File(game, "crash-reports/own.txt").isFile)
        assertTrue(File(directory, "unrelated-secret.txt").isFile)
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.containsAll(listOf("READ-ME-FIRST.txt", "incident/report.json", "session/events.jsonl", "minecraft/own.txt", "logs/latest.log", "CONTENTS.txt")))
            assertFalse(names.any { "unrelated" in it || it.endsWith(".zip") || it.endsWith(".lock") })
            assertFalse(names.contains("logs/fml-server-latest.log"))
            val summary = zip.getInputStream(zip.getEntry("READ-ME-FIRST.txt")).reader(Charsets.UTF_8).readText()
            assertTrue(summary.contains("Test Mod")); assertTrue(summary.contains("not a proven culprit")); assertTrue(summary.contains("Send the ZIP"))
            val log = zip.getInputStream(zip.getEntry("logs/latest.log")).reader().readText()
            assertFalse(log.contains("SECRET")); assertFalse(log.contains("PASSWORD")); assertTrue(log.length <= 1024 * 1024)
        }
        SupportBundle.write(directory, report, analysis, TracerConfig())
        assertEquals(listOf(archive.name), archive.parentFile.listFiles()!!.map { it.name })
        assertFalse(File(archive.parentFile, archive.name + ".partial").exists())
        assertEquals(archive, SupportBundle.state(directory)?.archive)
    }

    @Test fun `old incidents never attach logs from a later session even within two minutes`() {
        val game = temporary.newFolder()
        val root = File(game, "crash-tracer").apply { mkdirs() }
        val directory = File(root, "reports/old").apply { mkdirs() }
        val old = File(root, "sessions/session-old").apply { mkdirs() }
        File(old, "session.json").apply { writeText("{}"); setLastModified(1000) }
        val newer = File(root, "sessions/session-new").apply { mkdirs() }
        File(newer, "session.json").writeText("{}")
        File(game, "logs").mkdirs()
        File(game, "logs/latest.log").writeText("NEXT-JVM-LOG")
        val report = report("old", "session-old")
        LocalReportWriter().write(directory, report)
        SupportBundle.write(directory, report, null, TracerConfig())
        ZipFile(SupportBundle.archive(directory)).use { assertNull(it.getEntry("logs/latest.log")) }
    }

    @Test fun `archive remains valid and byte quota allows subsequent analysis without nesting archive`() {
        val directory = temporary.newFolder()
        val report = report()
        LocalReportWriter().write(directory, report)
        SupportBundle.write(directory, report, null, TracerConfig())
        val archive = SupportBundle.archive(directory)
        ArtifactBudget(directory, 1024 * 1024).json("analysis.json", mapOf("test" to true))
        SupportBundle.write(directory, report, null, TracerConfig())
        ZipFile(archive).use { assertNotNull(it.getEntry("incident/analysis.json")) }
    }

    @Test fun `summary distinguishes unknown cause memory exhaustion and stronger evidence`() {
        val oom = PlayerSummary.create(report(error = OutOfMemoryError()), null)
        assertTrue(oom.russian.first().contains("памяти"))
        val empty = AnalysisResult(incidentId = "test", fingerprint = "hash", rootException = null, suspects = emptyList(), findings = emptyList(), mappings = emptyList(), limitations = emptyList())
        assertTrue(PlayerSummary.create(report(), empty).russian[1].contains("не установлена"))
        val high = empty.copy(suspects = listOf(Suspect("testmod", "HIGH", emptyList())))
        assertTrue(PlayerSummary.create(report(), high).russian[1].contains("не доказана"))
    }

    @Test fun `pending analysis creates archive for new reports and backfills analyzed reports`() {
        val root = temporary.newFolder()
        val directory = File(root, "reports/incident").apply { mkdirs() }
        LocalReportWriter().write(directory, report())
        PendingAnalysis.process(directory)
        assertTrue(SupportBundle.archive(directory).isFile)
        assertFalse(File(directory, "report.json").exists())
        // Simulate a legacy analyzed incident that still has raw files, but no bundle.
        LocalReportWriter().write(directory, report())
        AnalysisEngine(LocalMappings(File(root, "mappings"))).analyze(directory, report(), 1024 * 1024)
        assertTrue(SupportBundle.archive(directory).delete())
        PendingAnalysis.process(directory)
        assertTrue(SupportBundle.archive(directory).isFile)
    }
}
