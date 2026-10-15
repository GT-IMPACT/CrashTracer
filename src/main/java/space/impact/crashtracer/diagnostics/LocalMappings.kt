package space.impact.crashtracer.diagnostics

import java.io.File

class LocalMappings(directory: File) : MappingResolver {
    private val methods = hashMapOf<String, String>()
    val sources = mutableListOf<String>()
    val limitations = mutableListOf<String>()

    init {
        for (name in listOf("methods.csv", "fields.csv")) {
            val file = File(directory, name)
            if (!file.isFile) continue
            try {
                require(file.length() <= 32L * 1024 * 1024) { "Mapping file too large" }
                file.useLines(Charsets.UTF_8) { lines ->
                    lines.take(500000).forEach { line ->
                        val row = csv(line)
                        if (row.size >= 2 && (row[0].startsWith("func_") || row[0].startsWith("field_"))) {
                            if (row[1].matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) methods[row[0]] = row[1]
                        }
                    }
                }
                sources.add("${file.name}: ${file.length()} bytes, modified ${file.lastModified()}")
            } catch (error: Exception) { limitations.add("${file.name}: ${error.javaClass.simpleName}") }
        }
        if (methods.isEmpty()) limitations.add("No local SRG → MCP mappings found; original names retained.")
    }

    override fun resolveFrame(frame: String): String = Regex("(?:func|field)_[0-9]+_[A-Za-z_]+")
        .replace(frame) { methods[it.value] ?: it.value }

    private fun csv(line: String): List<String> {
        val columns = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index++]
            when {
                char == '"' && quoted && index < line.length && line[index] == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { columns.add(field.toString()); field.setLength(0) }
                else -> field.append(char)
            }
        }
        columns.add(field.toString())
        return columns
    }
}
