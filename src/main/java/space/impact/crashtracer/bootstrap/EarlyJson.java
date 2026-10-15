package space.impact.crashtracer.bootstrap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Bounded fallback exception graph, written even when Kotlin cannot initialize. */
final class EarlyJson {
    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < Math.min(value.length(), 4096); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 32) out.append(' '); else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    static String graph(Throwable error, EarlyOptions options) {
        List<Throwable> values = new ArrayList<>();
        IdentityHashMap<Throwable, Integer> ids = new IdentityHashMap<>();
        values.add(error); ids.put(error, 0);
        StringBuilder nodes = new StringBuilder();
        boolean truncated = false;
        Integer rootCause = null;
        for (int i = 0; i < values.size(); i++) {
            Throwable value = values.get(i);
            Throwable cause = value.getCause();
            if (cause != null && !ids.containsKey(cause) && values.size() < options.maxExceptionNodes) {
                ids.put(cause, values.size()); values.add(cause);
            }
            if (cause != null && !ids.containsKey(cause)) truncated = true;
            StringBuilder suppressed = new StringBuilder();
            Throwable[] extras = value.getSuppressed();
            for (int j = 0; j < Math.min(extras.length, options.maxExceptionNodes); j++) {
                Throwable extra = extras[j];
                if (!ids.containsKey(extra) && values.size() < options.maxExceptionNodes) {
                    ids.put(extra, values.size()); values.add(extra);
                }
                if (ids.containsKey(extra)) {
                    if (suppressed.length() > 0) suppressed.append(',');
                    suppressed.append(ids.get(extra));
                } else truncated = true;
            }
            if (extras.length > options.maxExceptionNodes) truncated = true;
            if (i > 0) nodes.append(',');
            nodes.append("{\"id\":").append(i).append(",\"type\":").append(quote(value.getClass().getName()))
                    .append(",\"message\":").append(quote(value.getMessage())).append(",\"cause\":")
                    .append(ids.get(cause)).append(",\"suppressed\":[").append(suppressed).append("],\"frames\":[");
            StackTraceElement[] frames = value.getStackTrace();
            for (int j = 0; j < Math.min(frames.length, options.maxFramesPerException); j++) {
                if (nodes.length() > options.maxReportBytes / 4) { truncated = true; break; }
                if (j > 0) nodes.append(',');
                nodes.append(quote(frames[j].toString()));
            }
            if (frames.length > options.maxFramesPerException) truncated = true;
            nodes.append("]}");
        }
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable cursor = error;
        while (cursor != null && ids.containsKey(cursor) && visited.put(cursor, true) == null) {
            if (cursor.getCause() == null) { rootCause = ids.get(cursor); break; }
            cursor = cursor.getCause();
        }
        return "{\"root\":0,\"rootCause\":" + rootCause + ",\"truncated\":" + truncated + ",\"nodes\":[" + nodes + "]}";
    }
}
