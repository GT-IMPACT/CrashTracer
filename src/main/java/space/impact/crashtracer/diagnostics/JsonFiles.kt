package space.impact.crashtracer.diagnostics

import com.google.gson.GsonBuilder
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object JsonFiles {
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun write(file: File, value: Any, maxBytes: Long = 16L * 1024 * 1024) =
        text(file, maxBytes) { gson.toJson(value, it) }

    fun text(file: File, maxBytes: Long = 16L * 1024 * 1024, body: (java.io.Writer) -> Unit) {
        require(!Files.isSymbolicLink(file.toPath())) { "Refusing symbolic link output" }
        val temporary = File(file.parentFile, file.name + ".partial")
        require(!Files.isSymbolicLink(temporary.toPath())) { "Refusing symbolic link temporary file" }
        try {
            // Java 8's StreamEncoder may leave its stream open when close/flush hits
            // the quota. Close the raw handle independently so Windows can remove it.
            temporary.outputStream().use { raw ->
                LimitedOutput(raw, maxBytes).bufferedWriter(Charsets.UTF_8).use(body)
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            try { Files.deleteIfExists(temporary.toPath()) }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private class LimitedOutput(output: OutputStream, private var remaining: Long) : FilterOutputStream(output) {
        override fun write(value: Int) {
            if (remaining-- <= 0) throw IOException("Report size limit reached")
            out.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > remaining) throw IOException("Report size limit reached")
            out.write(bytes, offset, length)
            remaining -= length
        }
    }
}
