package space.impact.crashtracer.smoke;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.crash.CrashReport;
import net.minecraft.server.MinecraftServer;

/** Opt-in development fixture; never included in the distributed mod. */
@Mod(modid = "crashtracer_smoketest", name = "Crash Tracer Server Smoke Fixture", version = "1",
        dependencies = "required-after:crashtracer", acceptableRemoteVersions = "*")
public final class ServerSmokeMod {
    private final String scenario = System.getProperty("crashtracer.smoke", "start");
    private volatile boolean started;
    private int ticks;
    private static final List<byte[]> exhaustedHeap = new ArrayList<>();

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if ("fml".equals(scenario)) throw failure("FML initialization");
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void started(FMLServerStartedEvent event) {
        started = true;
        System.out.println("CRASHTRACER_SMOKE_SERVER_STARTED scenario=" + scenario);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (!started || event.phase != TickEvent.Phase.START) return;
        ticks++;
        if (ticks == 40) {
            if ("logs".equals(scenario)) {
                org.apache.logging.log4j.LogManager.getLogger("CrashTracerSmoke").warn("CRASHTRACER_SMOKE_LOG Authorization: Bearer fake-smoke-token");
                org.apache.logging.log4j.LogManager.getLogger("net.minecraft.crashtracer.smoke").error("CRASHTRACER_SMOKE_LOG password=fake-smoke-password");
            }
            if ("native".equals(scenario)) {
                System.out.println("CRASHTRACER_SMOKE_NATIVE_BEGIN");
                try {
                    Class<?> type = Class.forName("sun.misc.Unsafe");
                    java.lang.reflect.Field field = type.getDeclaredField("theUnsafe");
                    field.setAccessible(true);
                    type.getMethod("putAddress", long.class, long.class).invoke(field.get(null), 0L, 1L);
                } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
                throw new AssertionError("Native crash did not occur");
            }
            if ("oom".equals(scenario)) {
                System.out.println("CRASHTRACER_SMOKE_OOM_BEGIN");
                try {
                    while (true) exhaustedHeap.add(new byte[1024 * 1024]);
                } catch (OutOfMemoryError error) {
                    try { new CrashReport("Crash Tracer real heap exhaustion", error); }
                    finally { exhaustedHeap.clear(); }
                }
                System.out.println("CRASHTRACER_SMOKE_OOM_CAPTURED");
            }
            if ("deadlock".equals(scenario)) startDeadlock();
            if ("crash".equals(scenario)) throw failure("Server tick");
            if ("hang".equals(scenario)) {
                System.out.println("CRASHTRACER_SMOKE_HANG_BEGIN");
                try { Thread.sleep(16000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                System.out.println("CRASHTRACER_SMOKE_HANG_END");
            }
            if ("report".equals(scenario)) {
                Throwable error = failure("Explicit crash report");
                CrashReport report = new CrashReport("Crash Tracer server smoke report", error);
                report.saveToFile(new File("crash-reports/crashtracer-smoke.txt"));
                System.out.println("CRASHTRACER_SMOKE_REPORT_CREATED");
            }
        }
        if ("soak".equals(scenario) && ticks % 200 == 0) {
            new CrashReport("Crash Tracer soak report " + ticks, failure("soak"));
            System.out.println("CRASHTRACER_SMOKE_SOAK ticks=" + ticks);
        }
        if (ticks == ("soak".equals(scenario) ? 1200 : 120) && !"deadlock".equals(scenario)) {
            System.out.println("CRASHTRACER_SMOKE_STOPPING ticks=" + ticks);
            MinecraftServer.getServer().initiateShutdown();
        }
    }

    private static void startDeadlock() {
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        CountDownLatch ready = new CountDownLatch(2);
        Thread one = deadlockedThread("Smoke-deadlock-one", first, second, ready);
        Thread two = deadlockedThread("Smoke-deadlock-two", second, first, ready);
        one.start(); two.start();
        Thread cleanup = new Thread(() -> {
            try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
            one.interrupt(); two.interrupt();
            System.out.println("CRASHTRACER_SMOKE_DEADLOCK_RELEASED");
            MinecraftServer.getServer().initiateShutdown();
        }, "Smoke-deadlock-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        System.out.println("CRASHTRACER_SMOKE_DEADLOCK_BEGIN");
    }

    private static Thread deadlockedThread(String name, ReentrantLock own, ReentrantLock other, CountDownLatch ready) {
        Thread thread = new Thread(() -> {
            own.lock();
            try {
                ready.countDown(); ready.await();
                other.lockInterruptibly();
                try {} finally { other.unlock(); }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { own.unlock(); }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static RuntimeException failure(String stage) {
        IllegalArgumentException cause = new IllegalArgumentException("CRASHTRACER_SMOKE_ROOT " + stage);
        RuntimeException wrapper = new RuntimeException("CRASHTRACER_SMOKE_FAILURE " + stage, cause);
        wrapper.addSuppressed(new IllegalStateException("CRASHTRACER_SMOKE_SUPPRESSED"));
        return wrapper;
    }
}
