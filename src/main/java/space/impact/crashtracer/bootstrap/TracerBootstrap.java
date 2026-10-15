package space.impact.crashtracer.bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.channels.FileLock;

/** The only crash path required before Forgelin is ready. No Minecraft, Kotlin or logging dependencies. */
public final class TracerBootstrap {
    public interface Sink {
        void capture(File incident, String description, Throwable error, String source) throws Exception;
    }

    private static File session;
    private static Sink sink;
    private static boolean capturing;
    private static final class SeenError {
        final WeakReference<Throwable> error;
        final File incident;
        final Set<String> sources;
        SeenError(Throwable error, File incident, Set<String> sources) { this.error = new WeakReference<>(error); this.incident = incident; this.sources = sources; }
    }
    private static final List<SeenError> seen = new ArrayList<>();
    private static final String SESSION_ID = "session-" + UUID.randomUUID();
    private static EarlyOptions options = new EarlyOptions();
    private static final ArrayDeque<String[]> events = new ArrayDeque<>();
    private static final ArrayDeque<String[]> criticalEvents = new ArrayDeque<>();
    private static final Map<String, String> coremods = new LinkedHashMap<>();
    private static final Set<String> tweakers = new LinkedHashSet<>();
    private static String phase = "BOOTSTRAP";
    private static int persistedEvents;
    private static final AtomicLong droppedEvents = new AtomicLong();
    private static final ReentrantLock eventLock = new ReentrantLock();
    private static byte[] reserve = new byte[128 * 1024];
    private static FileOutputStream sessionLock;
    private static FileLock activeSessionLock;
    private static final ThreadLocal<Boolean> recording = new ThreadLocal<>();
    private static int incidents;
    private static volatile WeakReference<Throwable> lastLaunchFailure;

    private TracerBootstrap() {}

    public static synchronized void initialize(File gameDirectory) {
        if (session != null) return;
        try {
            options = EarlyOptions.load(new File(gameDirectory, "crash-tracer/config.json"));
            if (!options.enabled) return;
            File candidate = new File(gameDirectory, "crash-tracer/sessions/" + SESSION_ID);
            if (!candidate.mkdirs() && !candidate.isDirectory()) return;
            sessionLock = new FileOutputStream(new File(candidate, ".lock"));
            activeSessionLock = sessionLock.getChannel().lock();
            write(new File(candidate, "session.json"), "{\"schemaVersion\":1,\"sessionId\":\"" + SESSION_ID
                    + "\",\"startedAt\":" + System.currentTimeMillis() + ",\"phase\":\"BOOTSTRAP\"}");
            session = candidate;
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    Throwable original = lastLaunchFailure == null ? null : lastLaunchFailure.get();
                    boolean trappedExit = "cpw.mods.fml.relauncher.FMLSecurityManager$ExitTrappedException".equals(error.getClass().getName());
                    capture("Uncaught exception in " + thread.getName(), trappedExit && original != null ? original : error, "uncaught-exception");
                } finally {
                    if (previous != null) previous.uncaughtException(thread, error);
                    else if (!(error instanceof ThreadDeath)) {
                        System.err.println("Exception in thread \"" + thread.getName() + "\"");
                        error.printStackTrace(System.err);
                    }
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { write(new File(session, "closed.json"), "{\"closedAt\":" + System.currentTimeMillis() + "}"); }
                catch (Throwable ignored) {}
                try { activeSessionLock.release(); sessionLock.close(); } catch (Throwable ignored) {}
            }, "CrashTracer-shutdown"));
            event("SESSION", SESSION_ID, "", "STARTED");
        } catch (Throwable ignored) {
            // Diagnostics must never prevent Minecraft startup.
        }
    }

    public static synchronized File getSessionDirectory() {
        return session;
    }

    public static synchronized void install(Sink value) {
        sink = value;
    }

    public static synchronized void launchFailure(Throwable error) {
        if (error == null) return;
        lastLaunchFailure = new WeakReference<>(error);
        event("LAUNCH", "LaunchWrapper", error.getClass().getName(), "FAILED");
        capture("LaunchWrapper could not launch the game", error, "launchwrapper-failure");
    }

    public static synchronized EarlyOptions getOptions() { return options; }
    public static synchronized String getPhase() { return phase; }
    public static long getDroppedEvents() { return droppedEvents.get(); }

    public static synchronized void phase(String value) {
        phase = clip(value);
        event("PHASE", phase, "", "ENTER");
    }

    public static List<String[]> events() {
        if (!eventLock.tryLock()) return new ArrayList<>();
        try {
            List<String[]> snapshot = new ArrayList<>(criticalEvents);
            for (String[] event : events) if (!snapshot.contains(event)) snapshot.add(event);
            return snapshot;
        } finally { eventLock.unlock(); }
    }

    public static synchronized void coremod(String name, String source) {
        if (coremods.size() < 512 && (!coremods.containsKey(name) || !"unknown".equals(source))) coremods.put(clip(name), clip(source));
        event("COREMOD", name, source, "DISCOVERED");
    }
    public static synchronized Map<String, String> coremods() { return new LinkedHashMap<>(coremods); }
    public static synchronized void tweaker(String name) {
        if (tweakers.size() < 256) tweakers.add(clip(name));
        event("TWEAKER", name, "", "DISCOVERED");
    }
    public static synchronized List<String> tweakers() { return new ArrayList<>(tweakers); }

    public static void event(String kind, String target, String detail, String status) {
        if (Boolean.TRUE.equals(recording.get()) || !options.enabled) return;
        // Logging and transformer callbacks must never wait for a crash handler's monitor.
        if (!eventLock.tryLock()) { droppedEvents.incrementAndGet(); return; }
        recording.set(true);
        try {
            String[] entry = { Long.toString(System.currentTimeMillis()), clip(kind), clip(target), clip(detail), clip(status) };
            while (events.size() >= options.eventCapacity) { events.removeFirst(); droppedEvents.incrementAndGet(); }
            events.addLast(entry);
            if (status != null && (status.contains("FAILED") || "FML_ERROR".equals(kind) || "DEADLOCK".equals(status))) {
                while (criticalEvents.size() >= 128) criticalEvents.removeFirst();
                criticalEvents.addLast(entry);
            }
            if (session != null && persistedEvents < options.maxEventsOnDisk) {
                StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"timestamp\":").append(entry[0]);
                String[] keys = {"timestamp", "kind", "target", "detail", "status"};
                for (int i = 1; i < entry.length; i++) json.append(',').append(EarlyJson.quote(keys[i])).append(':').append(EarlyJson.quote(entry[i]));
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(session, "events.jsonl"), true), "UTF-8")) {
                    writer.write(json.append("}\n").toString());
                    persistedEvents++;
                }
            }
        } catch (Throwable ignored) {} finally { recording.remove(); eventLock.unlock(); }
    }

    public static synchronized void capture(String description, Throwable error, String source) {
        try { captureInternal(description, error, source); }
        catch (Throwable ignored) { capturing = false; }
    }

    private static void captureInternal(String description, Throwable error, String source) {
        if (session == null || error == null || capturing) return;
        // Graph traversal and deduplication also allocate under heap exhaustion.
        if (error instanceof OutOfMemoryError) reserve = null;
        List<Throwable> related = related(error);
        for (Iterator<SeenError> it = seen.iterator(); it.hasNext();) {
            SeenError recorded = it.next();
            Throwable existing = recorded.error.get();
            if (existing == null || !recorded.incident.isDirectory()) it.remove();
            else if (related.stream().anyMatch(value -> value == existing)) {
                event("DUPLICATE", recorded.incident.getName(), source, "LINKED");
                if (recorded.sources.size() < 16 && recorded.sources.add(source)) {
                    String observation = "{\"source\":" + EarlyJson.quote(source) + ",\"timestamp\":" + System.currentTimeMillis() + "}\n";
                    if (usedBytes(recorded.incident) + observation.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > options.maxReportBytes) return;
                    try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(recorded.incident, "sources.jsonl"), true), "UTF-8")) {
                        writer.write(observation);
                    } catch (Throwable ignored) {}
                }
                return;
            }
        }
        if (incidents >= options.maxIncidentsPerSession) return;
        capturing = true;
        FileOutputStream incidentLock = null;
        try {
            if (error instanceof OutOfMemoryError) reserve = null;
            File reports = new File(session.getParentFile().getParentFile(), "reports");
            File incident = new File(reports, System.currentTimeMillis() + "-" + UUID.randomUUID());
            if (!incident.mkdirs()) return;
            incidentLock = new FileOutputStream(new File(incident, ".lock"));
            incidentLock.getChannel().lock();
            // Persist a bounded minimal record first, even if the full Kotlin pipeline fails.
            write(new File(incident, "emergency.txt"), minimal(description, error, source));
            incidents++;
            Set<String> sources = new LinkedHashSet<>(); sources.add(source);
            for (Throwable value : related) seen.add(new SeenError(value, incident, sources));
            try {
                write(new File(incident, "early.json"), "{\"schemaVersion\":1,\"sessionId\":" + EarlyJson.quote(SESSION_ID)
                        + ",\"capturedAt\":" + System.currentTimeMillis() + ",\"source\":" + EarlyJson.quote(source)
                        + ",\"description\":" + EarlyJson.quote(description) + ",\"phase\":" + EarlyJson.quote(phase)
                        + ",\"thread\":" + EarlyJson.quote(Thread.currentThread().getName())
                        + ",\"exceptions\":" + EarlyJson.graph(error, options) + "}");
            } catch (Throwable ignored) {}
            event("INCIDENT", incident.getName(), error.getClass().getName(), source);
            if (sink != null && !(error instanceof OutOfMemoryError)) {
                try {
                    sink.capture(incident, description, error, source);
                } catch (Throwable failure) {
                    write(new File(incident, "collector-failure.txt"), failure.getClass().getName());
                }
            }
        } catch (Throwable ignored) {
            // Keep the original exception and its normal handler intact.
        } finally {
            try { if (incidentLock != null) incidentLock.close(); } catch (Throwable ignored) {}
            capturing = false;
        }
    }

    private static List<Throwable> related(Throwable error) {
        List<Throwable> values = new ArrayList<>();
        IdentityHashMap<Throwable, Boolean> ids = new IdentityHashMap<>();
        values.add(error); ids.put(error, true);
        for (int index = 0; index < values.size(); index++) {
            Throwable value = values.get(index);
            Throwable cause = value.getCause();
            if (cause != null && !ids.containsKey(cause) && values.size() < options.maxExceptionNodes) { values.add(cause); ids.put(cause, true); }
            for (Throwable extra : value.getSuppressed()) {
                if (values.size() >= options.maxExceptionNodes) break;
                if (!ids.containsKey(extra)) { values.add(extra); ids.put(extra, true); }
            }
        }
        return values;
    }

    private static String minimal(String description, Throwable error, String source) {
        StringBuilder text = new StringBuilder(4096);
        text.append("Crash Tracer — emergency record\nSource: ").append(clip(source))
                .append("\nDescription: ").append(clip(description))
                .append("\nException: ").append(error.getClass().getName()).append('\n');
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, 64); i++) text.append("  at ").append(frames[i]).append('\n');
        text.append("Minimal record only; full analysis may be unavailable.\n");
        return text.toString();
    }

    private static String clip(String text) {
        return text == null ? "" : text.substring(0, Math.min(text.length(), 1024));
    }

    private static void write(File file, String text) throws Exception {
        File temporary = new File(file.getParentFile(), file.getName() + ".partial");
        if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(temporary.toPath())) throw new IOException("Symbolic link output");
        long limit = Long.MAX_VALUE;
        if ("reports".equals(file.getParentFile().getParentFile().getName())) {
            limit = options.maxReportBytes - usedBytes(file.getParentFile()) + (file.isFile() ? file.length() : 0);
        }
        try {
            try (FileOutputStream raw = new FileOutputStream(temporary);
                 Writer writer = new OutputStreamWriter(new LimitedOutput(raw, limit), "UTF-8")) { writer.write(text); }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }

    private static long usedBytes(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile() && !Files.isSymbolicLink(file.toPath())) size += file.length();
        return size;
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private long remaining;
        LimitedOutput(OutputStream output, long remaining) { super(output); this.remaining = remaining; }
        @Override public void write(int value) throws IOException {
            if (remaining-- <= 0) throw new IOException("Incident quota reached");
            out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > remaining) throw new IOException("Incident quota reached");
            out.write(bytes, offset, length); remaining -= length;
        }
    }
}
