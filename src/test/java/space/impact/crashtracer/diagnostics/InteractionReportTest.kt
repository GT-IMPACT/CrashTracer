package space.impact.crashtracer.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class InteractionReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun report(): IncidentReport {
        val error = NullPointerException().apply { stackTrace = arrayOf(
            StackTraceElement("net.minecraft.network.NetHandlerPlayServer", "processPlayerBlockPlacement", "NetHandlerPlayServer.java", 620),
            StackTraceElement("net.minecraft.server.integrated.IntegratedServer", "tick", "IntegratedServer.java", 111)) }
        return IncidentReport(incidentId = "interaction", sessionId = "session-test", capturedAt = System.currentTimeMillis(),
            source = "minecraft-crash-report", description = "Ticking memory connection", thread = "Server thread",
            exceptions = ExceptionCollector.collect(error), context = ContextSnapshot("SERVER_STARTED", emptyList(), emptyList(), emptyMap(), emptyList()),
            suspects = emptyList(), limitations = emptyList())
    }

    @Test fun `old real crash shape gets useful category without inventing an item or culprit`() {
        val directory = temporary.newFolder()
        val oldJson = JsonFiles.gson.toJson(report())
        assertFalse(oldJson.contains("\"interaction\":"))
        val legacy = JsonFiles.gson.fromJson(oldJson, IncidentReport::class.java)
        assertNull(legacy.interaction)
        val analysis = AnalysisEngine(LocalMappings(temporary.newFolder())).analyze(directory, legacy, 1024 * 1024)
        val summary = PlayerSummary.create(legacy, analysis)
        assertEquals("Внутренний сервер остановлен при обработке правого клика.", summary.russian.first())
        assertEquals("Точная причина не установлена.", summary.russian[1])
        assertTrue(analysis.suspects.isEmpty())
        assertTrue(analysis.findings.any { it.contains("older report") })
        val srg = legacy.copy(exceptions = legacy.exceptions.copy(nodes = legacy.exceptions.nodes.map {
            it.copy(frames = it.frames.map { frame -> frame.replace("processPlayerBlockPlacement", "func_147346_a") })
        }))
        assertTrue(InteractionDetails.isRightClick(srg))
        // A remote packet error may only disconnect one player; do not claim the entire server stopped.
        assertFalse(PlayerSummary.create(legacy.copy(description = "Ticking connection"), analysis).russian.first().contains("остановлен"))
    }

    @Test fun `item and container transition survive JSON ZIP and analysis without blaming the item's mod`() {
        val item = ItemSnapshot("appliedenergistics2:item.ToolPriorityCard", "appeng.items.tools.ToolPriorityCard", 0, 1, true)
        val before = PlayerInteractionState(0, true, item, ContainerSnapshot("net.minecraft.inventory.ContainerPlayer", 0, 45, 2, 38, true))
        val after = before.copy(container = ContainerSnapshot("appeng.container.implementations.ContainerPriorityCard", 1, 1, 2, null, true))
        val report = report().copy(interaction = InteractionSnapshot(System.currentTimeMillis(),
            "net.minecraft.network.play.client.C08PacketPlayerBlockPlacement", "RIGHT_CLICK_AIR",
            mapOf("face" to "255", "x" to "-1"), item, before, after, listOf("NBT content excluded")))
        val directory = temporary.newFolder()
        LocalReportWriter().write(directory, report)
        val roundTrip = PendingAnalysis.readReport(File(directory, "report.json"), 1024 * 1024)!!
        assertEquals(report.interaction, roundTrip.interaction)
        val analysis = AnalysisEngine(LocalMappings(temporary.newFolder())).analyze(directory, roundTrip, 1024 * 1024)
        assertTrue(analysis.suspects.isEmpty())
        assertTrue(analysis.findings.any { it.contains("container changed") })
        assertTrue(analysis.findings.any { it.contains("no slot matching") && it.contains("does not prove") })
        val summary = PlayerSummary.create(roundTrip, analysis)
        assertTrue(summary.russian.first().contains("Shift+ПКМ"))
        assertTrue(summary.russian[1].contains("ToolPriorityCard"))
        assertTrue(summary.russian[1].contains("ContainerPriorityCard"))
        assertTrue(summary.russian[1].startsWith("Точная причина не установлена."))
        SupportBundle.write(directory, roundTrip, analysis, TracerConfig())
        ZipFile(SupportBundle.archive(directory)).use { zip ->
            assertNotNull(zip.getEntry("incident/interaction.json"))
            val detail = zip.getInputStream(zip.getEntry("incident/interaction.txt")).reader().readText()
            assertTrue(detail.contains("matching container slot: not found"))
            assertTrue(detail.contains("Before processing")); assertTrue(detail.contains("At failure"))
            val readme = zip.getInputStream(zip.getEntry("READ-ME-FIRST.txt")).reader(Charsets.UTF_8).readText()
            assertTrue(readme.contains("Shift + right click")); assertTrue(readme.contains("ContainerPriorityCard"))
        }
    }

    @Test fun `truncated slot scans never report a confirmed missing slot`() {
        val state = PlayerInteractionState(0, false, null, ContainerSnapshot("HugeContainer", 4, 2000, 1, null, false))
        val report = report().copy(interaction = InteractionSnapshot(1, "C08", "RIGHT_CLICK_BLOCK", emptyMap(), null, state, state, emptyList()))
        val analysis = AnalysisEngine(LocalMappings(temporary.newFolder())).analyze(temporary.newFolder(), report, 1024 * 1024)
        assertFalse(analysis.findings.any { it.contains("no slot matching") })
    }

    @Test fun `archive summary follows recorded game language with English fallback`() {
        val report = report()
        val english = PlayerSummary.create(report.copy(context = report.context.copy(environment = mapOf("client.language" to "en_US"))), null).text()
        assertTrue(english.contains("short summary")); assertFalse(english.any { it in 'А'..'я' })
        val russian = PlayerSummary.create(report.copy(context = report.context.copy(environment = mapOf("client.language" to "ru_RU"))), null).text()
        assertTrue(russian.contains("краткая сводка")); assertFalse(russian.contains("short summary"))
        assertEquals(english, PlayerSummary.create(report, null).text())
    }
}
