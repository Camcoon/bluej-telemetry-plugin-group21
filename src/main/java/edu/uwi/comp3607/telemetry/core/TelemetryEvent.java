package edu.uwi.comp3607.telemetry.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable representation of a single telemetry observation.
 *
 * <p>Events are the universal record type used throughout the plugin. Every
 * BlueJ lifecycle change, compilation result, execution outcome and editor
 * sample is normalized into an instance of this record. The {@code details}
 * map carries event-type-specific attributes (for example the derived error
 * category of a compilation problem), while {@code project} and {@code target}
 * identify the affected project and class, package or file.</p>
 *
 * <p>Instances are safe to share between threads: the details map is copied
 * into an unmodifiable, insertion-ordered map at construction time, and all
 * components are immutable.</p>
 *
 * @param timestamp the UTC instant at which the observation was made
 * @param type      the event type identifier (for example {@code COMPILE_STARTED})
 * @param project   the normalized project directory, or {@code null} if unknown
 * @param target    the class, package or file the event concerns, or {@code null}
 * @param details   type-specific attributes; may be {@code null}, in which case it is treated as empty
 */
public record TelemetryEvent(Instant timestamp, String type, String project, String target, Map<String, Object> details) {
    /**
     * Validates the required components and defensively copies the details map.
     *
     * @throws IllegalArgumentException if {@code timestamp} or {@code type} is {@code null}
     */
    public TelemetryEvent {
        if (timestamp == null || type == null) throw new IllegalArgumentException("timestamp/type required");
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details == null ? Map.of() : details));
    }
}
