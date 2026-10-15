package space.impact.crashtracer.diagnostics

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.BaseConfiguration
import org.junit.Assert.*
import org.junit.Test
import space.impact.crashtracer.bootstrap.TracerBootstrap

class LogCaptureTest {
    @Test fun `non additive logger history is captured and masked`() {
        val context = LogManager.getContext(false) as LoggerContext
        val configuration = context.configuration as BaseConfiguration
        val name = "crashtracer-test-nonadditive"
        configuration.addLogger(name, LoggerConfig(name, Level.ALL, false))
        context.updateLoggers()
        try {
            LogCapture().use {
                it.attach()
                context.getLogger(name).warn("Authorization: Bearer fake-unit-token")
                val event = TracerBootstrap.events().last { value -> value[1] == "LOG" && value[2] == name }
                assertEquals("WARN", event[4])
                assertFalse(event[3].contains("fake-unit-token"))
                assertTrue(event[3].contains("[redacted]"))
            }
        } finally {
            configuration.removeLogger(name)
            context.updateLoggers()
        }
    }
}
