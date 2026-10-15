package space.impact.crashtracer.client

import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.resources.I18n
import net.minecraft.crash.CrashReport
import space.impact.crashtracer.bootstrap.TracerBootstrap
import space.impact.crashtracer.diagnostics.SupportBundle
import space.impact.crashtracer.diagnostics.TracerRuntime
import java.io.File

/** Only used when BetterCrashes is absent. A fatal crash still requires quitting. */
class GuiCrashSummary(private val report: CrashReport) : GuiScreen() {
    @Volatile var closed = false
        private set
    private val incident = TracerBootstrap.incidentFor(report.crashCause)
    private var folderError: String? = null

    override fun initGui() {
        buttonList.clear()
        buttonList.add(GuiButton(1734001, width / 2 - 140, height - 76, 280, 20, I18n.format("crashtracer.gui.preparing")))
        buttonList.add(GuiButton(1, width / 2 - 140, height - 52, 280, 20, I18n.format("crashtracer.gui.report")))
        buttonList.add(GuiButton(0, width / 2 - 140, height - 28, 280, 20, I18n.format("crashtracer.gui.quit")))
        TracerRuntime.requestSupport(report.crashCause)
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawDefaultBackground()
        drawCenteredString(fontRendererObj, I18n.format("crashtracer.gui.title"), width / 2, 20, 0xFFFFFF)
        val state = incident?.let(SupportBundle::state)
        val paragraphs = state?.summary?.let { if (I18n.format("crashtracer.language") == "ru") it.russian else it.english }
            ?: listOf(I18n.format("crashtracer.gui.wait"))
        val textWidth = minOf(560, width - 32).coerceAtLeast(100)
        var y = 44
        for (paragraph in paragraphs) {
            for (line in fontRendererObj.listFormattedStringToWidth(paragraph, textWidth)) {
                if (y + 9 > height - 94) break
                fontRendererObj.drawString(line.toString(), (width - textWidth) / 2, y, 0xDDDDDD)
                y += 11
            }
            y += 6
        }
        for (entry in buttonList) {
            val button = entry as GuiButton
            if (button.id == 1734001) {
                button.enabled = state?.archive != null || state?.failed == true
                button.displayString = I18n.format(when {
                    state?.archive != null -> "crashtracer.gui.open"
                    state?.failed == true -> "crashtracer.gui.retry"
                    else -> "crashtracer.gui.preparing"
                })
            }
            if (button.id == 1) button.enabled = report.file?.isFile == true
        }
        folderError?.let { drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(it, width - 16), width / 2, height - 90, 0xFF9999) }
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            0 -> closed = true
            1 -> report.file?.let(::open)
            1734001 -> {
                val archive = incident?.let(SupportBundle::state)?.archive
                if (archive == null) TracerRuntime.requestSupport(report.crashCause) else open(archive.parentFile)
            }
        }
    }

    override fun keyTyped(character: Char, keyCode: Int) { /* Escape must not resume the crashed game. */ }

    private fun open(file: File) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(file)
            else check(org.lwjgl.Sys.openURL(file.toURI().toString()))
            folderError = null
        } catch (_: Exception) { folderError = I18n.format("crashtracer.gui.folderFailed") + " " + file.absolutePath }
    }
}
