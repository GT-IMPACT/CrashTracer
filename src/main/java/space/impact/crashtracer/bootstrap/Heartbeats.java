package space.impact.crashtracer.bootstrap;

import java.util.concurrent.ConcurrentHashMap;

/** No IO in per-tick hooks. Only the watchdog writes diagnostics. */
public final class Heartbeats {
    public static final class Pulse {
        public final Thread thread;
        public volatile long startedAt;
        public volatile boolean active;
        Pulse(Thread thread) { this.thread = thread; }
    }
    private static final ConcurrentHashMap<String, Pulse> pulses = new ConcurrentHashMap<>();

    public static void begin(String lane) {
        try {
            Thread current = Thread.currentThread();
            Pulse pulse = pulses.compute(lane, (key, old) -> old == null || old.thread != current ? new Pulse(current) : old);
            pulse.startedAt = System.nanoTime();
            pulse.active = true;
        } catch (Throwable ignored) {}
    }

    public static void end(String lane) {
        try { Pulse pulse = pulses.get(lane); if (pulse != null) pulse.active = false; }
        catch (Throwable ignored) {}
    }

    public static java.util.Collection<Pulse> snapshot() { return pulses.values(); }
}
