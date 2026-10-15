package space.impact.crashtracer.diagnostics

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import space.impact.crashtracer.bootstrap.TracerBootstrap
import space.impact.crashtracer.bootstrap.TransformerTrace

object TracerRuntime : CrashCollector {
    @Volatile private var context: ContextSnapshot? = null
    @Volatile private var config = TracerConfig()
    @Volatile private var cachedForensics = ForensicsSnapshot(emptyList(), emptyList(), emptyList())
    private var initialized = false
    private var watchdog: Watchdog? = null
    private var logCapture: LogCapture? = null
    private val background = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "CrashTracer-analysis").apply { isDaemon = true }
    }

    @Synchronized fun initialize() {
        if (initialized) return
        val session = TracerBootstrap.getSessionDirectory() ?: return
        try {
            val root = session.parentFile.parentFile
            config = TracerConfig.load(root)
            if (!config.enabled) return
            initialized = true
            TracerBootstrap.install { directory, description, error, source -> capture(directory, description, error, source) }
            val early = TracerBootstrap.getOptions()
            early.maxIncidentsPerSession = config.maxIncidentsPerSession
            early.maxExceptionNodes = config.maxExceptionNodes
            early.maxFramesPerException = config.maxFramesPerException
            early.eventCapacity = config.eventCapacity
            early.maxEventsOnDisk = config.maxEventsOnDisk
            early.maxReportBytes = config.maxReportBytes.toInt()
            early.bytecodeExport = config.bytecodeExport
            optional("world-events") { net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(RuntimeEvents) }
            optional("log-history") { if (config.logCapture) { logCapture = LogCapture(); logCapture?.attach() } }
            optional("bettercrashes") { BetterCrashesIntegration().initialize() }
            optional("transformers") { if (config.experimentalTransformers) TransformerTrace.install(config.maxBytecodeFiles, config.maxBytecodeBytes) }
            optional("watchdog") { watchdog = Watchdog(config); watchdog?.start() }
            optional("storage-maintenance") {
                background.scheduleWithFixedDelay({
                    try { StorageMaintenance.clean(root, session, config) } catch (_: Throwable) {}
                }, 30, 300, TimeUnit.SECONDS)
            }
            optional("shutdown-hook") {
                Runtime.getRuntime().addShutdownHook(Thread({
                    watchdog?.close()
                    logCapture?.close()
                    background.shutdownNow()
                }, "CrashTracer-runtime-shutdown"))
            }
        } catch (failure: Throwable) {
            TracerBootstrap.event("RUNTIME", "initialize", failure.javaClass.name, "PARTIAL")
        }
    }

    private inline fun optional(name: String, action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            TracerBootstrap.event("ADAPTER", name, failure.javaClass.name, "UNAVAILABLE")
        }
    }

    fun phase(name: String) {
        if (TracerBootstrap.getSessionDirectory() == null) return
        try {
            initialize()
            TracerBootstrap.phase(name)
            val snapshot = ContextCollector.collect(name)
            context = snapshot
            // Cache registry metadata while the loader is healthy. Crash-time collection reads the event ring only.
            cachedForensics = ForensicsCollector(snapshot.mods).collect(emptySet())
            TracerBootstrap.getSessionDirectory()?.let { JsonFiles.write(File(it, "context.json"), snapshot, config.maxReportBytes) }
        } catch (failure: Throwable) {
            TracerBootstrap.event("RUNTIME", name, failure.javaClass.name, "CONTEXT_UNAVAILABLE")
        }
    }

    override fun capture(directory: File, description: String, error: Throwable, source: String) {
        val limits = mutableListOf<String>()
        val original = context ?: ContextSnapshot(TracerBootstrap.getPhase(), emptyList(), emptyList(), emptyMap(), emptyList())
        val snapshot = original.copy(phase = TracerBootstrap.getPhase(), environment = original.environment.filterKeys { !it.startsWith("world.") } +
            RuntimeEvents.world.mapKeys { "world.${it.key}" } + ContextCollector.language())
        if (config.experimentalTransformers) limits.add("ASM tracing starts at FML preInit. Name/Mixin transformers, registry dispatchers and implementations without inspectable bytecode retain their identity and are not wrapped; early or later-registered transforms may be absent.")
        val exceptions = ExceptionCollector.collect(error, config.maxExceptionNodes, config.maxFramesPerException,
            (config.maxReportBytes / 8).toInt())
        val threads = if (config.threadDump) try {
            ThreadCollector.collect(config.maxThreads, space.impact.crashtracer.bootstrap.Heartbeats.snapshot()
                .filter { it.thread.isAlive }.map { it.thread.id })
        } catch (failure: Throwable) {
            limits.add("Thread snapshot unavailable: ${failure.javaClass.name}"); emptyList()
        } else emptyList()
        val runtime = Runtime.getRuntime()
        val events = TracerBootstrap.events().map { it.toList() }
        val forensic = cachedForensics.copy(events = events,
            limitations = cachedForensics.limitations + "Event ring evictions: ${TracerBootstrap.getDroppedEvents()}; disk log stops at ${config.maxEventsOnDisk} events.",
            mixins = cachedForensics.mixins + events.filter { it[1] == "MIXIN" }.map {
                MixinRecord(it[3].substringAfter("config=", "unknown"), it[3].substringBefore(" config="),
                    it[2], it[4], null, null, emptyList())
            })
        val report = IncidentReport(incidentId = directory.name,
            sessionId = TracerBootstrap.getSessionDirectory()?.name.orEmpty(), capturedAt = System.currentTimeMillis(),
            source = source, description = description.take(4096), thread = Thread.currentThread().name,
            exceptions = exceptions, context = snapshot, suspects = emptyList(),
            limitations = limits + listOf("Heavy attribution is deferred; see analysis.json after analysis.",
                "BetterCrashes may have deobfuscated the Throwable before capture.",
                "Exception graph truncated: ${exceptions.truncated}"),
            forensics = forensic, threads = threads,
            memory = mapOf("heapMax" to runtime.maxMemory(), "heapCommitted" to runtime.totalMemory(), "heapFree" to runtime.freeMemory()),
            interaction = try { InteractionCapture.forError(error) } catch (_: Throwable) { null })
        LocalReportWriter(config.maxReportBytes).write(directory, report)
        SupportBundle.pending(directory, report)
        // The immutable record already exists. Enrichment happens on the worker after the crash handler returns.
        background.schedule({ analyzeDirectory(directory) }, 2, TimeUnit.SECONDS)
    }

    private fun analyzeDirectory(directory: File) {
        try {
            val root = TracerBootstrap.getSessionDirectory()?.parentFile?.parentFile ?: return
            val report = PendingAnalysis.readReport(File(directory, "report.json"), config.maxReportBytes)
            if (report != null) {
                val classes = report.exceptions.nodes.flatMap { it.frames }.map { it.substringBefore('(').substringBeforeLast('.', "") }.filter { it.isNotEmpty() }.toSet()
                val graph = ForensicsCollector(report.context.mods).collect(classes)
                ArtifactBudget(directory, config.maxReportBytes).json("runtime-mixins.json", graph)
            }
            PendingAnalysis.process(directory, config, File(root, "mappings/1.7.10"))
        } catch (failure: Throwable) {
            TracerBootstrap.event("ANALYSIS", directory.name, failure.javaClass.name, "FAILED")
        }
    }

    @JvmStatic fun requestSupport(error: Throwable) {
        try {
            val directory = TracerBootstrap.incidentFor(error) ?: return
            background.execute {
                try {
                    val root = directory.parentFile.parentFile
                    val settings = if (initialized) config else TracerConfig.load(root)
                    PendingAnalysis.process(directory, settings, File(root, "mappings/1.7.10"))
                    SupportBundle.refresh(directory, settings)
                } catch (failure: Throwable) { TracerBootstrap.event("ARCHIVE", directory.name, failure.javaClass.name, "FAILED") }
            }
        } catch (_: Throwable) { }
    }

    fun analyzePending() {
        if (!config.analyzeOnStartup || TracerBootstrap.getSessionDirectory() == null) return
        background.execute {
            try {
                val session = TracerBootstrap.getSessionDirectory() ?: return@execute
                val root = session.parentFile.parentFile
                context?.let { NativeCrashCollector.collect(root.parentFile, root, it, config) }
                PendingAnalysis.process(File(root, "reports"), config)
                StorageMaintenance.clean(root, session, config)
            } catch (failure: Throwable) { TracerBootstrap.event("ANALYSIS", "startup", failure.javaClass.name, "FAILED") }
        }
    }
}
