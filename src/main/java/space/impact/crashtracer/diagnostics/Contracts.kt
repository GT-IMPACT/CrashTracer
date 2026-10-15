package space.impact.crashtracer.diagnostics

import java.io.File

interface CrashIntegration { fun isAvailable(): Boolean; fun initialize() }
interface EvidenceProvider { fun collect(classes: Set<String>): ForensicsSnapshot }
interface MappingResolver { fun resolveFrame(frame: String): String }
interface ReportWriter { fun write(directory: File, report: IncidentReport) }
interface CrashCollector { fun capture(directory: File, description: String, error: Throwable, source: String) }
