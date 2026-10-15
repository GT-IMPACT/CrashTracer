package space.impact.crashtracer.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArtifactBudget(private val directory: File, private val limit: Long) {
    private fun available(file: File): Long {
        // JsonFiles truncates this temporary output before writing; an interrupted previous
        // attempt must not consume the replacement's entire allowance.
        val temporaryName = file.name + ".partial"
        val used = directory.listFiles()?.filter {
            it.name != temporaryName && !SupportBundle.isArchive(it.name) && it.isFile && !Files.isSymbolicLink(it.toPath())
        }?.sumOf { it.length() } ?: 0L
        return (limit - used + if (file.isFile) file.length() else 0L).also {
            if (it <= 0) throw IOException("Incident storage quota reached")
        }
    }
    fun json(name: String, value: Any) { val file = File(directory, name); JsonFiles.write(file, value, available(file)) }
    fun text(name: String, body: (java.io.Writer) -> Unit) { val file = File(directory, name); JsonFiles.text(file, available(file), body) }
}
