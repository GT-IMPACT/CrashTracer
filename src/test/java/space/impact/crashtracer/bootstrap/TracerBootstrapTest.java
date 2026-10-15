package space.impact.crashtracer.bootstrap;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class TracerBootstrapTest {
    @Test
    public void emergencyRecordSurvivesSinkFailureAndDeduplicatesByIdentity() throws Exception {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        File game = Files.createTempDirectory("crashtracer-test").toFile();
        try {
            AtomicInteger delegated = new AtomicInteger();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> delegated.incrementAndGet());
            TracerBootstrap.initialize(game);
            assertTrue(new File(TracerBootstrap.getSessionDirectory(), "session.json").isFile());
            TracerBootstrap.install((directory, description, error, source) -> {
                throw new IllegalStateException("writer failed");
            });
            Throwable first = new RuntimeException("same message");
            TracerBootstrap.capture("first", first, "test");
            TracerBootstrap.capture("duplicate", first, "test");
            // An independent incident with the same message must still be captured.
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),
                    new RuntimeException("same message"));
            assertEquals(1, delegated.get());
            File[] incidents = new File(game, "crash-tracer/reports").listFiles();
            assertNotNull(incidents);
            assertEquals(2, incidents.length);
            for (File incident : incidents) {
                assertTrue(new File(incident, "emergency.txt").length() > 0);
                assertTrue(new File(incident, "collector-failure.txt").isFile());
            }
        } finally {
            TracerBootstrap.install(null);
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }
}
