package edu.uwi.comp3607.telemetry.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EventFactory {
    private EventFactory() { }

    public static TelemetryEvent event(Instant when, String type, String project, String target,
                                       Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!(pairs[i] instanceof String key)) throw new IllegalArgumentException("key must be String");
            details.put(key, pairs[i + 1]);
        }
        return new TelemetryEvent(when, type, project, target, details);
    }

    public static String errorCategory(String message) {
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("cannot find symbol") || m.contains("cannot resolve symbol")) return "MISSING_SYMBOL";
        if (m.contains("incompatible types") || m.contains("cannot be converted")) return "TYPE_MISMATCH";
        if (m.contains("missing return")) return "MISSING_RETURN";
        if (m.contains("expected") || m.contains("illegal start") || m.contains("reached end of file")) return "SYNTAX";
        if (m.contains("does not exist") || m.contains("package ")) return "MISSING_IMPORT";
        return "OTHER";
    }
}
