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
