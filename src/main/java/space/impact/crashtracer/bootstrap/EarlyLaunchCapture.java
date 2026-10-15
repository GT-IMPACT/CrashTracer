package space.impact.crashtracer.bootstrap;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/** LaunchWrapper logs fatal transformation errors before System.exit hides their cause.
 * Kept separate from the JDK-only bootstrap and installed only after Log4j is available. */
public final class EarlyLaunchCapture extends AbstractAppender {
    private static EarlyLaunchCapture installed;

    private EarlyLaunchCapture() { super("CrashTracer-launch-failures", null, null, true); }

    public static synchronized void attach() {
        if (installed != null || TracerBootstrap.getSessionDirectory() == null) return;
        // LaunchWrapper lives in the parent loader; using the coremod's logger context
        // attaches to a different root under Log4j's ClassLoaderContextSelector.
        Object context = LogManager.getContext(net.minecraft.launchwrapper.Launch.class.getClassLoader(), false);
        if (!(context instanceof LoggerContext)) return;
        EarlyLaunchCapture appender = new EarlyLaunchCapture();
        appender.start();
        ((LoggerContext) context).getLogger("LaunchWrapper").addAppender(appender);
        installed = appender;
    }

    @Override public void append(LogEvent event) {
        try {
            if (event.getLevel() != Level.ERROR && event.getLevel() != Level.FATAL) return;
            if (!"LaunchWrapper".equals(event.getLoggerName()) || event.getThrown() == null) return;
            TracerBootstrap.launchFailure(event.getThrown());
        } catch (Throwable ignored) {}
    }
}
