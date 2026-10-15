package space.impact.crashtracer.diagnostics

object SensitiveText {
    private val assignment = Regex("(?i)((?:access[_-]?token|client[_-]?token|password|secret|authorization|session[_-]?id)\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)")
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*")
    fun clean(value: String): String {
        // Mask the complete Bearer credential before assignment matching consumes its prefix.
        val withoutBearer = bearer.replace(value) { it.groupValues[1] + "[redacted]" }
        return assignment.replace(withoutBearer) { it.groupValues[1] + "[redacted]" }
    }
}
