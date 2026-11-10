package edu.uwi.comp3607.telemetry.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public record TelemetryEvent(Instant timestamp, String type, String project, String target,
                             Map<String, Object> details) {
    public TelemetryEvent {
        if (timestamp == null || type == null) throw new IllegalArgumentException("timestamp/type required");
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details == null ? Map.of() : details));
    }
}
