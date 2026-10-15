package space.impact.crashtracer.bootstrap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class BootstrapIsolationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void disabledModeDoesNotInstallHandler() throws Exception { probe("disabled"); }
    @Test public void inaccessibleStorageDoesNotBreakStartup() throws Exception { probe("blocked"); }
    @Test public void concurrentCaptureHonorsIncidentAndEventLimits() throws Exception { probe("concurrent"); }

    private void probe(String mode) throws Exception {
        File game = temporary.newFolder(mode);
        String classpath = new File(BootstrapIsolationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + new File(TracerBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File output = new File(game, "probe.log");
        Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", classpath, Probe.class.getName(), mode, game.getPath()).redirectErrorStream(true).redirectOutput(output).start();
        try {
            assertTrue("Probe timed out", child.waitFor(30, TimeUnit.SECONDS));
            assertEquals(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8), 0, child.exitValue());
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    /** Runs with only JDK and bootstrap classes, with independent static state per process. */
    public static class Probe {
        public static void main(String[] args) throws Exception {
            File game = new File(args[1]);
            File root = new File(game, "crash-tracer");
            Thread.UncaughtExceptionHandler previous = (thread, error) -> {};
            Thread.setDefaultUncaughtExceptionHandler(previous);
            if (args[0].equals("blocked")) Files.write(root.toPath(), new byte[]{1});
            else {
                root.mkdirs();
                String config = args[0].equals("disabled") ? "{\"enabled\":false}" :
                    "{\"maxIncidentsPerSession\":2,\"eventCapacity\":16,\"maxEventsOnDisk\":4}";
                Files.write(new File(root, "config.json").toPath(), config.getBytes(StandardCharsets.UTF_8));
            }
            TracerBootstrap.initialize(game);
            if (!args[0].equals("concurrent")) {
                check(TracerBootstrap.getSessionDirectory() == null, "Unexpected session");
                check(Thread.getDefaultUncaughtExceptionHandler() == previous, "Changed handler");
                TracerBootstrap.capture("ignored", new RuntimeException(), "probe");
                return;
            }
            Thread[] threads = new Thread[12];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < 20; j++) {
                        TracerBootstrap.event("PROBE", "target", "detail", "OK");
                        TracerBootstrap.capture("probe", new RuntimeException("independent"), "probe");
                    }
                });
                threads[i].start();
            }
            for (Thread thread : threads) thread.join();
            check(new File(root, "reports").listFiles(File::isDirectory).length == 2, "Incident quota");
            check(TracerBootstrap.events().size() <= 16, "Ring capacity");
            File events = new File(TracerBootstrap.getSessionDirectory(), "events.jsonl");
            check(Files.readAllLines(events.toPath(), StandardCharsets.UTF_8).size() <= 4, "Disk event quota");
            check(TracerBootstrap.getDroppedEvents() > 0, "No dropped event accounting");
        }
        private static void check(boolean result, String message) {
            if (!result) { System.err.println(message); System.exit(1); }
        }
    }
}
