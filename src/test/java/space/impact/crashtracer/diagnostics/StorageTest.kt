package space.impact.crashtracer.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class StorageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report(directory: File): IncidentReport {
        directory.mkdirs()
        return IncidentReport(incidentId = directory.name, sessionId = "session-test", capturedAt = 1,
            source = "test", description = "test", thread = "main",
            exceptions = ExceptionCollector.collect(IllegalStateException("test")),
            context = ContextSnapshot("TEST", emptyList(), emptyList(), emptyMap(), emptyList()),
            suspects = emptyList(), limitations = emptyList())
    }

    @Test fun `quota failure preserves previous complete artifact and removes temporary output`() {
        val directory = temporary.newFolder()
        val output = File(directory, "result.json").apply { writeText("previous") }
        try {
            JsonFiles.text(output, 12) { it.write("я".repeat(100)) }
            fail("UTF-8 byte quota must fail")
        } catch (_: java.io.IOException) {}
        assertEquals("previous", output.readText())
        assertFalse(File(directory, "result.json.partial").exists())
        ArtifactBudget(directory, 32).text("next.txt") { it.write("next") }
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 32)
    }

    @Test fun `failed atomic replacement preserves target and permits retry`() {
        val directory = temporary.newFolder()
        val output = File(directory, "report.json").apply { mkdir(); File(this, "keep").writeText("keep") }
        try {
            JsonFiles.write(output, mapOf("test" to true))
            fail("Cannot replace a nonempty directory")
        } catch (_: java.io.IOException) {}
        assertEquals("keep", File(output, "keep").readText())
        assertFalse(File(directory, "report.json.partial").exists())
    }

    @Test fun `retention preserves active report current session and unrelated directories`() {
        val root = temporary.newFolder()
        fun artifact(path: String, marker: String, time: Long) = File(root, path).apply {
            mkdirs(); File(this, marker).writeText("{}"); setLastModified(time)
        }
        val old = artifact("reports/old", "early.json", 1000)
        val active = artifact("reports/active", "early.json", 2000)
        val newest = artifact("reports/newest", "early.json", 3000)
        val unrelated = File(root, "reports/user-files").apply { mkdirs(); File(this, "keep").writeText("keep") }
        val current = artifact("sessions/session-current", "session.json", 1000)
        val latest = artifact("sessions/session-latest", "session.json", 3000)
        val stale = artifact("sessions/session-stale", "session.json", 2000)
        RandomAccessFile(File(active, ".lock"), "rw").use { access ->
            access.channel.lock().use {
                active.setLastModified(2000)
                StorageMaintenance.clean(root, current, TracerConfig(maxReports = 1, maxSessions = 1))
                assertFalse(old.exists()); assertTrue(active.exists()); assertTrue(newest.exists())
                assertTrue(current.exists()); assertTrue(latest.exists()); assertFalse(stale.exists())
                assertTrue(unrelated.exists())
            }
        }
        StorageMaintenance.clean(root, current, TracerConfig(maxReports = 1, maxSessions = 1))
        assertFalse(active.exists())
    }

    @Test fun `locked pending report is skipped then analyzed after unlock`() {
        val directory = temporary.newFolder("locked")
        LocalReportWriter().write(directory, report(directory))
        RandomAccessFile(File(directory, ".lock"), "rw").use { access ->
            access.channel.lock().use {
                assertTrue(PendingAnalysis.process(directory).isEmpty())
                assertFalse(File(directory, "analysis.json").exists())
            }
        }
        assertEquals(1, PendingAnalysis.process(directory).size)
    }

    @Test fun `early record recovers a corrupted full report`() {
        val directory = temporary.newFolder("recovery")
        val original = report(directory)
        JsonFiles.write(File(directory, "early.json"), EarlyIncident(1, original.sessionId, 1,
            "early-test", "early survives", "BOOTSTRAP", "main", original.exceptions))
        File(directory, "report.json").writeText("{broken")
        assertEquals(1, PendingAnalysis.process(directory).size)
        assertEquals("early-test", PendingAnalysis.readReport(File(directory, "report.json"), 1000000)!!.source)
    }

    @Test fun `interrupted full write does not prevent early recovery at the storage limit`() {
        val directory = temporary.newFolder("partial-recovery")
        val original = report(directory)
        JsonFiles.write(File(directory, "early.json"), EarlyIncident(1, original.sessionId, 1,
            "early-test", "early survives", "BOOTSTRAP", "main", original.exceptions))
        File(directory, "report.json.partial").writeBytes(ByteArray(1024 * 1024))
        assertEquals(1, PendingAnalysis.process(directory, TracerConfig(maxReportBytes = 1024 * 1024)).size)
        assertFalse(File(directory, "report.json.partial").exists())
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1024 * 1024)
    }

    @Test fun `future schema is not silently replaced by an early v1 record`() {
        val directory = temporary.newFolder("future")
        val original = report(directory)
        JsonFiles.write(File(directory, "early.json"), EarlyIncident(1, original.sessionId, 1,
            "early-test", "early", "BOOTSTRAP", "main", original.exceptions))
        File(directory, "report.json").writeText("{\"schemaVersion\":2}")
        assertTrue(PendingAnalysis.process(directory).isEmpty())
        assertEquals("{\"schemaVersion\":2}", File(directory, "report.json").readText())
        assertFalse(File(directory, "analysis.json").exists())
    }

    @Test fun `original v1 reports without newly added fields remain readable`() {
        val directory = temporary.newFolder("old-v1")
        val json = JsonFiles.gson.toJsonTree(report(directory)).asJsonObject
        json.remove("threads"); json.remove("memory")
        json.getAsJsonObject("context").apply { remove("coremods"); remove("tweakers") }
        val file = File(directory, "report.json")
        file.writeText(json.toString())
        val restored = PendingAnalysis.readReport(file, 1000000)!!
        assertTrue(restored.threads.isEmpty()); assertTrue(restored.memory.isEmpty())
        assertTrue(restored.context.coremods.isEmpty()); assertTrue(restored.context.tweakers.isEmpty())
    }

    @Test fun `native logs import once and oversized logs do not consume quota`() {
        val game = temporary.newFolder("game")
        val root = File(game, "crash-tracer").apply { mkdir() }
        val context = report(temporary.newFolder()).context
        File(game, "hs_err_pid123.log").writeText("# A fatal error has been detected by the Java Runtime Environment\n# SIGSEGV")
        File(game, "hs_err_pid456.log").writeBytes(ByteArray(300000))
        val config = TracerConfig(maxReportBytes = 1024 * 1024)
        NativeCrashCollector.collect(game, root, context, config)
        NativeCrashCollector.collect(game, root, context, config)
        val incidents = File(root, "reports").listFiles()!!
        assertEquals(1, incidents.size)
        assertTrue(File(incidents[0], "native-hs_err_pid123.log").isFile)
        assertEquals("jvm-native-log", PendingAnalysis.readReport(File(incidents[0], "report.json"), config.maxReportBytes)!!.source)
    }

    @Test fun `native import respects another writer and retries after unlock`() {
        val game = temporary.newFolder("native-lock")
        val root = File(game, "crash-tracer").apply { mkdir() }
        File(game, "hs_err_pid789.log").writeText("native test")
        val context = report(temporary.newFolder()).context
        RandomAccessFile(File(root, "native-imports.lock"), "rw").use { access ->
            access.channel.lock().use {
                NativeCrashCollector.collect(game, root, context, TracerConfig())
                assertFalse(File(root, "native-imports.json").exists())
            }
        }
        NativeCrashCollector.collect(game, root, context, TracerConfig())
        assertEquals(1, File(root, "reports").listFiles()!!.size)
    }

    @Test fun `retention and native import never follow a Windows directory junction`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = temporary.newFolder("junction-root")
        val outside = temporary.newFolder("outside-reports")
        val sentinel = File(outside, "keep.txt").apply { writeText("preserve") }
        File(outside, "early.json").writeText("{}")
        val reports = File(root, "reports").apply { mkdir() }
        val junction = File(reports, "old-link")
        val script = "New-Item -ItemType Junction -Path '${junction.path.replace("'", "''")}' -Target '${outside.path.replace("'", "''")}' -ErrorAction Stop | Out-Null"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(String(process.inputStream.readBytes()), 0, process.exitValue())
        try {
            junction.setLastModified(1000)
            File(reports, "new-report").apply { mkdir(); File(this, "early.json").writeText("{}"); setLastModified(5000) }
            StorageMaintenance.clean(root, File(root, "sessions/current"), TracerConfig(maxReports = 1))
            assertEquals("preserve", sentinel.readText())
            val game = temporary.newFolder("junction-game")
            val log = File(game, "hs_err_pid987.log").apply { writeText("native") }
            val nativeLink = File(reports, "native-hs_err_pid987-${log.lastModified()}")
            java.nio.file.Files.move(junction.toPath(), nativeLink.toPath())
            try {
                NativeCrashCollector.collect(game, root, report(temporary.newFolder()).context, TracerConfig())
                assertFalse(File(outside, "report.json").exists())
                assertEquals("preserve", sentinel.readText())
            } finally { java.nio.file.Files.deleteIfExists(nativeLink.toPath()) }
        } finally { java.nio.file.Files.deleteIfExists(junction.toPath()) }
    }
}
