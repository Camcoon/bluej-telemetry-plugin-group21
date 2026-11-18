package edu.uwi.comp3607.telemetry.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory helpers for constructing {@link TelemetryEvent} instances and for
 * deriving stable, coarse-grained error categories from raw compiler messages.
 *
 * <p>The class is stateless and exposes only static helpers. The category
 * heuristic is intentionally conservative: messages that do not match any
 * known pattern are reported as {@code OTHER} rather than guessed.</p>
 */
public final class EventFactory {
    /** Prevents instantiation; all functionality is provided by static methods. */
    private EventFactory() { }

    /**
     * Creates an event from an even number of alternating key/value detail pairs.
     *
     * <p>Keys must be strings and are stored in insertion order so that the
     * serialized JSON report has a stable field order.</p>
     *
     * @param when    the timestamp assigned to the event
     * @param type    the event type identifier
     * @param project the project directory the event belongs to, or {@code null}
     * @param target  the affected class, package or file, or {@code null}
     * @param pairs   alternating detail keys and values; may be empty
     * @return the newly created immutable event
     * @throws IllegalArgumentException if {@code pairs} has odd length or a key is not a {@link String}
     */
    public static TelemetryEvent event(Instant when, String type, String project, String target, Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!(pairs[i] instanceof String key)) throw new IllegalArgumentException("key must be String");
            details.put(key, pairs[i + 1]);
        }
        return new TelemetryEvent(when, type, project, target, details);
    }

    /**
     * Derives a stable error category by searching a compiler message for known
     * phrases, using a locale-independent lowercase comparison.
     *
     * <p>Recognized categories are {@code MISSING_SYMBOL}, {@code TYPE_MISMATCH},
     * {@code MISSING_RETURN}, {@code SYNTAX}, {@code MISSING_IMPORT} and
     * {@code OTHER}. Because the mapping is text based, categories are
     * approximate and may change with the compiler or its language settings.</p>
     *
     * @param message the raw compiler diagnostic message; may be {@code null}
     * @return the derived category name
     */
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
