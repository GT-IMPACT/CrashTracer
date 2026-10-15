package space.impact.crashtracer.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.crash.CrashReport
import net.minecraft.launchwrapper.Launch
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.Display
import org.lwjgl.opengl.GL11
import space.impact.crashtracer.bootstrap.TracerBootstrap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object ClientCrashPresenter {
    /** Runs inside vanilla's fatal handler, before its normal log output and process exit. */
    @JvmStatic fun showFallback(report: CrashReport) {
        if (Launch.classLoader.getResource("vfyjxf/bettercrashes/utils/GuiProblemScreen.class") != null) return
        try {
            val mc = Minecraft.getMinecraft()
            if (!Display.isCreated() || mc.fontRenderer == null || mc.entityRenderer == null) return
            if (report.file == null) {
                val name = "crash-${SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(Date())}-client.txt"
                report.saveToFile(File(mc.mcDataDir, "crash-reports/$name"))
            }
            TracerBootstrap.linkMinecraftReport(report.crashCause, report.file)
            space.impact.crashtracer.bootstrap.Heartbeats.end("client")
            mc.integratedServer?.initiateShutdown()
            mc.setIngameNotInFocus()
            val screen = GuiCrashSummary(report)
            mc.currentScreen = screen
            var lastWidth = -1
            var lastHeight = -1
            while (!screen.closed && !Display.isCloseRequested()) {
                val width = Display.getWidth().coerceAtLeast(1)
                val height = Display.getHeight().coerceAtLeast(1)
                if (width != lastWidth || height != lastHeight) {
                    mc.displayWidth = width
                    mc.displayHeight = height
                    mc.framebuffer.createBindFramebuffer(width, height)
                    val scaled = ScaledResolution(mc, width, height)
                    screen.setWorldAndResolution(mc, scaled.scaledWidth, scaled.scaledHeight)
                    lastWidth = width
                    lastHeight = height
                }
                screen.handleInput()
                mc.framebuffer.bindFramebuffer(true)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
                mc.entityRenderer.setupOverlayRendering()
                val scaled = ScaledResolution(mc, width, height)
                screen.drawScreen(Mouse.getX() * scaled.scaledWidth / width,
                    scaled.scaledHeight - Mouse.getY() * scaled.scaledHeight / height - 1, 0f)
                mc.framebuffer.unbindFramebuffer()
                mc.framebuffer.framebufferRender(width, height)
                Display.update()
                Display.sync(30)
            }
        } catch (failure: Throwable) {
            // A broken renderer or a very early startup failure must retain vanilla's exit path.
            TracerBootstrap.event("GUI", "fallback", failure.javaClass.name, "UNAVAILABLE")
        }
    }
}
