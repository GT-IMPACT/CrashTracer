package space.impact.crashtracer.clientsmoke

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent
import net.minecraft.client.Minecraft
import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.crash.CrashReport
import net.minecraft.util.ChatComponentText
import net.minecraftforge.client.ClientCommandHandler
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Explicit manual test fixture. Never packaged in the main mod. */
@Mod(modid = "crashtracer_clienttest", name = "Crash Tracer Client Test Fixture", version = "1",
    dependencies = "required-after:crashtracer", acceptableRemoteVersions = "*",
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter")
object ClientSmokeMod {
    private val clientAction = AtomicReference<String?>()
    private val serverAction = AtomicReference<String?>()
    private var archiveProbeTicks = 0
    private var archiveProbeError: Throwable? = null
    private val actions = setOf("crash", "hang", "servercrash", "serverhang", "report", "bettercrashes")

    @Mod.EventHandler
    fun initialize(event: FMLInitializationEvent) {
        if (!FMLCommonHandler.instance().side.isClient) return
        ClientCommandHandler.instance.registerCommand(object : CommandBase() {
            override fun getCommandName() = "cttest"
            override fun getCommandUsage(sender: ICommandSender) = "/cttest <${actions.joinToString("|")}>"
            override fun getRequiredPermissionLevel() = 0
            override fun canCommandSenderUseCommand(sender: ICommandSender) = true
            override fun processCommand(sender: ICommandSender, args: Array<String>) {
                val action = args.singleOrNull()
                if (action !in actions) {
                    sender.addChatMessage(ChatComponentText(getCommandUsage(sender)))
                    return
                }
                if (action!!.startsWith("server") && !Minecraft.getMinecraft().isSingleplayer) {
                    sender.addChatMessage(ChatComponentText("This action requires a singleplayer world."))
                    return
                }
                if (action == "bettercrashes" && !Loader.isModLoaded("bettercrashes")) {
                    sender.addChatMessage(ChatComponentText("BetterCrashes is not installed."))
                    return
                }
                val queue = if (action.startsWith("server")) serverAction else clientAction
                val message = if (queue.compareAndSet(null, action)) "Queued test: $action" else "A test is already queued."
                sender.addChatMessage(ChatComponentText(message))
            }
        })
        FMLCommonHandler.instance().bus().register(this)
    }

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        if (System.getProperty("crashtracer.clientSmoke") in setOf("archive", "archive-startup", "archive-fallback", "archive-fallback-crash")) archiveProbe()
        when (clientAction.getAndSet(null)) {
            "crash" -> throw failure("client tick")
            "hang" -> hang()
            "report" -> {
                val report = CrashReport("Crash Tracer manual client report", failure("explicit report"))
                val file = File(Minecraft.getMinecraft().mcDataDir, "crash-reports/crashtracer-client-${System.currentTimeMillis()}.txt")
                file.parentFile.mkdirs()
                val saved = report.saveToFile(file)
                Minecraft.getMinecraft().thePlayer?.addChatMessage(ChatComponentText("CrashReport saved=$saved: ${file.absolutePath}"))
            }
            "bettercrashes" -> {
                val report = CrashReport("Crash Tracer manual BetterCrashes report", failure("BetterCrashes output"))
                Class.forName("vfyjxf.bettercrashes.utils.CrashUtils").getMethod("outputReport", CrashReport::class.java).invoke(null, report)
            }
        }
    }

    private fun archiveProbe() {
        val mc = Minecraft.getMinecraft()
        archiveProbeTicks++
        if (archiveProbeTicks == 1) {
            if (mc.isFullScreen) mc.toggleFullscreen()
            org.lwjgl.opengl.Display.setDisplayMode(org.lwjgl.opengl.DisplayMode(854, 480))
            mc.resize(854, 480)
        }
        if (archiveProbeTicks == 30) {
            val report = CrashReport("Crash Tracer manual archive UI probe", failure("archive UI"))
            archiveProbeError = report.crashCause
            val scenario = System.getProperty("crashtracer.clientSmoke")
            if (scenario == "archive-fallback-crash") {
                Thread({
                    val deadline = System.currentTimeMillis() + 20000
                    while (System.currentTimeMillis() < deadline) {
                        val incident = space.impact.crashtracer.bootstrap.TracerBootstrap.incidentFor(report.crashCause)
                        val archive = incident?.let(space.impact.crashtracer.diagnostics.SupportBundle::state)?.archive
                        val screen = mc.currentScreen
                        if (archive?.isFile == true && screen is space.impact.crashtracer.client.GuiCrashSummary) {
                            println("CRASHTRACER_FALLBACK_FATAL_PROBE archive=${archive.name} vanilla=${report.file?.isFile}")
                            screen.javaClass.getDeclaredField("closed").apply { isAccessible = true }.setBoolean(screen, true)
                            return@Thread
                        }
                        Thread.sleep(100)
                    }
                    error("Fatal fallback probe timed out")
                }, "CrashTracer fallback observer").apply { isDaemon = true }.start()
                mc.displayCrashReport(report)
                return
            }
            val screen = if (scenario == "archive-fallback") {
                report.saveToFile(File(mc.mcDataDir, "crash-reports/crashtracer-fallback-${System.currentTimeMillis()}.txt"))
                space.impact.crashtracer.client.GuiCrashSummary(report)
            } else {
                Class.forName("vfyjxf.bettercrashes.utils.CrashUtils").getMethod("outputReport", CrashReport::class.java).invoke(null, report)
                val screenType = if (scenario == "archive-startup") "GuiInitErrorScreen" else "GuiCrashScreen"
                Class.forName("vfyjxf.bettercrashes.utils.$screenType").getConstructor(CrashReport::class.java).newInstance(report)
            }
            mc.displayGuiScreen(screen as net.minecraft.client.gui.GuiScreen)
        }
        if (archiveProbeTicks == 200) {
            val incident = space.impact.crashtracer.bootstrap.TracerBootstrap.incidentFor(archiveProbeError)
            val recorded = space.impact.crashtracer.diagnostics.PendingAnalysis.readReport(File(incident, "report.json"), 16L * 1024 * 1024)!!
            check(recorded.context.environment["client.language"] == mc.gameSettings.language) { "Game language was not captured" }
            val archive = space.impact.crashtracer.diagnostics.SupportBundle.state(incident)?.archive ?: error("Archive missing")
            check(incident.listFiles()!!.all { it.name == ".lock" || it.name == "share" }) { "Raw incident files remain after verified ZIP" }
            println("CRASHTRACER_ARCHIVE_ONLY_PASS incident=${incident.name}")
            java.util.zip.ZipFile(archive).use { zip ->
                val readme = zip.getInputStream(zip.getEntry("READ-ME-FIRST.txt")).reader(Charsets.UTF_8).readText()
                check(readme.contains(if (mc.gameSettings.language.startsWith("ru")) "краткая сводка" else "short summary"))
                if (!mc.gameSettings.language.startsWith("ru")) check(readme.none { it in 'А'..'я' })
            }
            println("CRASHTRACER_LANGUAGE_PASS language=${mc.gameSettings.language}")
            val field = cpw.mods.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.gui.GuiScreen::class.java, "buttonList", "field_146292_n")
            val buttons = field.get(mc.currentScreen) as List<*>
            for (entry in buttons) {
                val button = entry as net.minecraft.client.gui.GuiButton
                println("CRASHTRACER_UI_PROBE button=${button.id} enabled=${button.enabled} text=${button.displayString}")
            }
            check(buttons.any { it is net.minecraft.client.gui.GuiButton && it.id == 1734001 && it.enabled }) { "Support archive button was not ready" }
            check(buttons.any { it is net.minecraft.client.gui.GuiButton && it.id == 1 && it.enabled }) { "Minecraft report button is missing" }
            val suffix = when (System.getProperty("crashtracer.clientSmoke")) { "archive-startup" -> "-startup"; "archive-fallback" -> "-fallback"; else -> "" }
            println(net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, "support-bundle-ui$suffix.png", mc.displayWidth, mc.displayHeight, mc.framebuffer).unformattedText)
        }
        if (archiveProbeTicks == 220) mc.shutdown()
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        when (serverAction.getAndSet(null)) {
            "servercrash" -> throw failure("integrated server tick")
            "serverhang" -> hang()
        }
    }

    private fun hang() {
        try { Thread.sleep(16000) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun failure(stage: String) = RuntimeException("CRASHTRACER_CLIENT_TEST $stage",
        IllegalArgumentException("CRASHTRACER_CLIENT_TEST_ROOT $stage")).apply {
        addSuppressed(IllegalStateException("CRASHTRACER_CLIENT_TEST_SUPPRESSED"))
    }
}
