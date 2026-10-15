package space.impact.crashtracer.diagnostics

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.appender.AbstractAppender
import space.impact.crashtracer.bootstrap.TracerBootstrap

class LogCapture : AbstractAppender("CrashTracer-history", null, null, true), AutoCloseable {
    private var context: LoggerContext? = null
    private var targets = emptyList<LoggerConfig>()
    fun attach() {
        val selected = LogManager.getContext(false) as? LoggerContext ?: return
        val configuration = selected.configuration
        context = selected
        // Minecraft loggers have additivity=false and never reach the root appender.
        targets = (listOf(configuration.getLoggerConfig("")) + configuration.loggers.values.filter { !it.isAdditive }).distinct()
        start()
        targets.forEach { it.addAppender(this, Level.WARN, null) }
        selected.updateLoggers()
    }
    override fun append(event: LogEvent) {
        try {
            if (event.level != Level.WARN && event.level != Level.ERROR && event.level != Level.FATAL) return
            val text = SensitiveText.clean(event.message?.formattedMessage.orEmpty().take(4096))
            TracerBootstrap.event("LOG", event.loggerName, text, event.level.name)
            event.thrown?.let {
                TracerBootstrap.event("LOG_EXCEPTION", event.loggerName, it.javaClass.name + ": " + SensitiveText.clean(it.message.orEmpty()), event.level.name)
            }
        } catch (_: Throwable) {}
    }
    override fun close() {
        targets.forEach { it.removeAppender(name) }
        context?.updateLoggers()
        stop()
    }
}
