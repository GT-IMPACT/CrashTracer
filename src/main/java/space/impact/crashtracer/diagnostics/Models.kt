package space.impact.crashtracer.diagnostics

data class ModSnapshot(val id: String, val name: String, val version: String, val source: String?,
    val size: Long? = null, val modifiedAt: Long? = null)

data class ContextSnapshot(
    val phase: String,
    val mods: List<ModSnapshot>,
    val transformers: List<String>,
    val environment: Map<String, String>,
    val limitations: List<String>,
    val coremods: List<String> = emptyList(),
    val tweakers: List<String> = emptyList(),
)

data class ExceptionNode(
    val id: Int,
    val type: String,
    val message: String?,
    val frames: List<String>,
    val cause: Int?,
    val suppressed: List<Int>,
)

data class ExceptionSnapshot(val root: Int, val rootCause: Int?, val nodes: List<ExceptionNode>, val truncated: Boolean)
data class Suspect(val modId: String, val confidence: String, val evidence: List<String>)

data class MixinRecord(val config: String, val mixin: String, val target: String, val status: String,
    val priority: Int?, val source: String?, val owners: List<String>)
data class ForensicsSnapshot(val mixins: List<MixinRecord>, val events: List<List<String>>,
    val limitations: List<String>)
data class ThreadSnapshot(val id: Long, val name: String, val state: String, val lock: String?,
    val lockOwner: String?, val deadlocked: Boolean, val frames: List<String>,
    val lockOwnerId: Long? = null, val lockOwnerAlive: Boolean? = null)

data class IncidentReport(
    val schemaVersion: Int = 1,
    val incidentId: String,
    val sessionId: String,
    val capturedAt: Long,
    val source: String,
    val description: String,
    val thread: String,
    val exceptions: ExceptionSnapshot,
    val context: ContextSnapshot,
    val suspects: List<Suspect>,
    val limitations: List<String>,
    val forensics: ForensicsSnapshot? = null,
    val threads: List<ThreadSnapshot> = emptyList(),
    val memory: Map<String, Long> = emptyMap(),
)
