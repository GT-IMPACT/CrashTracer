package space.impact.crashtracer.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun report(id: String) = IncidentReport(incidentId = id, sessionId = "session-test", capturedAt = System.currentTimeMillis(),
        source = "test", description = "Archive lifecycle", thread = "main", exceptions = ExceptionCollector.collect(IllegalStateException("original")),
        context = ContextSnapshot("SERVER_STARTED", emptyList(), emptyList(), emptyMap(), emptyList()), suspects = emptyList(), limitations = emptyList())

    @Test fun `verified ZIP becomes sole diagnostic storage and force reanalysis stays archive only`() {
        val directory = temporary.newFolder("incident")
        val report = report(directory.name)
        LocalReportWriter().write(directory, report)
        val original = File(directory, "report.json").readText()
        PendingAnalysis.process(directory)
        assertEquals(setOf(".lock", "share"), directory.list()!!.toSet())
        SupportBundle.verify(SupportBundle.archive(directory), 20L * 1024 * 1024)
        assertEquals(original, SupportBundle.readIncident(directory, "report.json", 16L * 1024 * 1024))
        assertEquals(1, PendingAnalysis.process(directory, force = true).values.sumOf { it.size })
        assertEquals(setOf(".lock", "share"), directory.list()!!.toSet())
        assertEquals(report.incidentId, PendingAnalysis.readReport(File(directory, "report.json"), 16L * 1024 * 1024)!!.incidentId)
    }

    @Test fun `archive output failure preserves every original and last good archive`() {
        val directory = temporary.newFolder("failure")
        val report = report(directory.name)
        LocalReportWriter().write(directory, report)
        SupportBundle.write(directory, report, null, TracerConfig())
        val previous = SupportBundle.archive(directory).readBytes()
        LocalReportWriter().write(directory, report)
        val originals = directory.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes().toList() }
        val blocked = File(SupportBundle.archive(directory).path + ".partial").apply { mkdir() }
        File(blocked, "unrelated").writeText("keep")
        try { SupportBundle.write(directory, report, null, TracerConfig()); fail("Expected output failure") } catch (_: Exception) { }
        originals.forEach { (name, bytes) -> assertEquals(bytes, File(directory, name).readBytes().toList()) }
        assertArrayEquals(previous, SupportBundle.archive(directory).readBytes())
        assertTrue(File(blocked, "unrelated").isFile)
    }

    @Test fun `bad CRC prevents cleanup even when ZIP directory and entry names are readable`() {
        val directory = temporary.newFolder("crc")
        val report = report(directory.name)
        LocalReportWriter().write(directory, report)
        val target = SupportBundle.archive(directory).apply { parentFile.mkdirs() }
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((name, text) in mapOf("READ-ME-FIRST.txt" to "CRC-PROBE-MARKER", "CONTENTS.txt" to "contents", "incident/report.json" to JsonFiles.gson.toJson(report))) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
        val bytes = target.readBytes()
        val marker = "CRC-PROBE-MARKER".toByteArray()
        val start = (0..bytes.size - marker.size).first { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        bytes[start] = (bytes[start].toInt() xor 1).toByte()
        target.writeBytes(bytes)
        try { SupportBundle.write(directory, report, null, TracerConfig()); fail("Bad CRC accepted") } catch (_: Exception) { }
        assertTrue(File(directory, "report.json").isFile)
        assertTrue(File(directory, "stacktrace.txt").isFile)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun `omitted oversized original prevents deletion of other incident originals`() {
        val directory = temporary.newFolder("oversized")
        val report = report(directory.name)
        LocalReportWriter().write(directory, report)
        File(directory, "threads.txt").writeBytes(ByteArray(20000))
        SupportBundle.write(directory, report, null, TracerConfig(maxReportBytes = 16384))
        assertTrue(SupportBundle.archive(directory).isFile)
        assertTrue(File(directory, "report.json").isFile)
        assertEquals(20000, File(directory, "threads.txt").length().toInt())
    }

    @Test fun `late vanilla report refresh preserves archived evidence and original vanilla file`() {
        val game = temporary.newFolder("game")
        val directory = File(game, "crash-tracer/reports/late").apply { mkdirs() }
        val report = report(directory.name)
        LocalReportWriter().write(directory, report)
        SupportBundle.write(directory, report, null, TracerConfig())
        assertFalse(File(directory, "report.json").exists())
        val vanilla = File(game, "crash-reports/crash-server.txt").apply { parentFile.mkdirs(); writeText("vanilla report") }
        File(directory, "minecraft-report-name.txt").writeText(vanilla.name)
        SupportBundle.refresh(directory, TracerConfig())
        assertFalse(File(directory, "minecraft-report-name.txt").exists())
        assertEquals("vanilla report", vanilla.readText())
        ZipFile(SupportBundle.archive(directory)).use { zip ->
            assertNotNull(zip.getEntry("minecraft/${vanilla.name}"))
            assertNotNull(zip.getEntry("incident/stacktrace.txt"))
        }
    }

    @Test fun `repeated cached analysis keeps finalized ZIP unchanged and loads ready state`() {
        val directory = temporary.newFolder("cached")
        LocalReportWriter().write(directory, report(directory.name))
        PendingAnalysis.process(directory)
        val archive = SupportBundle.archive(directory)
        val before = archive.readBytes()
        assertEquals(1, PendingAnalysis.process(directory).values.sumOf { it.size })
        assertArrayEquals(before, archive.readBytes())
        assertEquals(archive, SupportBundle.state(directory)?.archive)
        assertFalse(File(directory, "report.json").exists())
    }

    @Test fun `retention recognizes archive only incidents`() {
        val root = temporary.newFolder("retention")
        val current = File(root, "sessions/session-current").apply { mkdirs(); File(this, "session.json").writeText("{}") }
        val old = File(root, "reports/old").apply { mkdirs() }
        val newest = File(root, "reports/newest").apply { mkdirs() }
        for (directory in listOf(old, newest)) {
            val report = report(directory.name)
            LocalReportWriter().write(directory, report)
            SupportBundle.write(directory, report, null, TracerConfig())
        }
        old.setLastModified(1000); newest.setLastModified(2000)
        StorageMaintenance.clean(root, current, TracerConfig(maxReports = 1))
        assertFalse(old.exists()); assertTrue(SupportBundle.archive(newest).isFile)
    }

    @Test fun `already compacted incidents do not starve later legacy incidents within batch limit`() {
        val reports = temporary.newFolder("reports")
        val directories = (1..3).map { id -> File(reports, "incident-$id").apply {
            mkdirs()
            val report = report(name)
            LocalReportWriter().write(this, report)
            AnalysisEngine(LocalMappings(File(reports, "mappings"))).analyze(this, report, 1024 * 1024)
        } }
        for (expected in 1..3) {
            PendingAnalysis.process(reports, TracerConfig(maxPendingReports = 1))
            assertEquals(expected, directories.count { SupportBundle.archive(it).isFile })
        }
        assertTrue(directories.all { !File(it, "report.json").exists() })
    }
}
