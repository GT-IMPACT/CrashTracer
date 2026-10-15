package space.impact.crashtracer.bootstrap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read only bootstrap primitives before any third party runtime is available. */
public final class EarlyOptions {
    public boolean enabled = true;
    public int maxIncidentsPerSession = 32;
    public int maxExceptionNodes = 64;
    public int maxFramesPerException = 256;
    public int eventCapacity = 512;
    public int maxEventsOnDisk = 10000;
    public int maxReportBytes = 16 * 1024 * 1024;
    public boolean experimentalTransformers = false;
    public boolean bytecodeExport = false;
    public int maxBytecodeFiles = 64;
    public int maxBytecodeBytes = 4 * 1024 * 1024;

    public static EarlyOptions load(File file) {
        EarlyOptions options = new EarlyOptions();
        try {
            if (!file.isFile() || file.length() > 65536) return options;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            options.enabled = bool(json, "enabled", true);
            options.experimentalTransformers = bool(json, "experimentalTransformers", false);
            options.bytecodeExport = bool(json, "bytecodeExport", false);
            options.maxBytecodeFiles = number(json, "maxBytecodeFiles", 64, 0, 256);
            options.maxBytecodeBytes = number(json, "maxBytecodeBytes", 4 * 1024 * 1024, 1024, 16 * 1024 * 1024);
            options.maxIncidentsPerSession = number(json, "maxIncidentsPerSession", 32, 1, 256);
            options.maxExceptionNodes = number(json, "maxExceptionNodes", 64, 1, 128);
            options.maxFramesPerException = number(json, "maxFramesPerException", 256, 1, 512);
            options.eventCapacity = number(json, "eventCapacity", 512, 16, 8192);
            options.maxEventsOnDisk = number(json, "maxEventsOnDisk", 10000, 0, 100000);
            options.maxReportBytes = number(json, "maxReportBytes", 16 * 1024 * 1024, 1024 * 1024, 64 * 1024 * 1024);
        } catch (Throwable ignored) {}
        return options;
    }

    private static boolean bool(String json, String key, boolean fallback) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }

    private static int number(String json, String key, int fallback, int min, int max) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        if (!matcher.find()) return fallback;
        try { return Math.max(min, Math.min(max, Integer.parseInt(matcher.group(1)))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
