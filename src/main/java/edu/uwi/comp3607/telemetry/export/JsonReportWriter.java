package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import edu.uwi.comp3607.telemetry.core.TelemetryEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonReportWriter implements ReportWriter {
    @Override public void write(SessionSnapshot s, Path path) throws IOException {
        Files.writeString(path, toJson(s), StandardCharsets.UTF_8);
    }

    public String toJson(SessionSnapshot s) {
        Map<String,Object> root = new LinkedHashMap<>();
        Map<String,Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", s.version);
        meta.put("sessionId", s.sessionId);
        meta.put("studentId", s.studentId);
        meta.put("startedAtUtc", s.startedAtUtc);
        meta.put("snapshotAtUtc", s.snapshotAtUtc);
        meta.put("endedAtUtc", s.endedAtUtc);
        meta.put("totalDurationMillis", s.durationMillis);
        meta.put("activeCodingTimeMillisEstimated", s.activeMillis);
        meta.put("idleTimeMillisEstimated", s.idleMillis);
        meta.put("totalCompilationAttempts", s.compileAttempts);
        root.put("sessionMetadata", meta);
        Map<String,Object> compilations = new LinkedHashMap<>();
        compilations.put("successful", s.compileSucceeded);
        compilations.put("failed", s.compileFailed);
        compilations.put("firstErrorsRecorded", s.compileErrors);
        compilations.put("firstWarningsRecorded", s.compileWarnings);
        compilations.put("successRatePercent", round(s.compileSuccessRatePercent));
        compilations.put("errorCategoriesDerived", s.errorBreakdown);
        root.put("compilationSummary", compilations);
        Map<String,Object> editor = new LinkedHashMap<>();
        editor.put("pollIntervalSeconds", 30);
        editor.put("wordSampleIntervalSeconds", 300);
        editor.put("idleThresholdSeconds", 150);
        editor.put("editFrequencyByFiveMinuteWindow", s.editsPerFiveMinuteWindow);
        editor.put("estimatedPositiveNetNewWords", s.estimatedNewWords);
        editor.put("estimatedWordsPerActiveMinute", round(s.estimatedWordsPerActiveMinute));
        editor.put("limitations", List.of("Same-length edits cannot be detected",
                "Positive net word counts are not a count of exact inserted words",
                "Idle intervals are classified retroactively after 150 seconds"));
        root.put("editorActivityEstimated", editor);
        root.put("executionOutcomeSummary", s.executionOutcomes);
        List<Object> eventList = new ArrayList<>();
        for (TelemetryEvent e : s.events) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("timestampUtc", e.timestamp());
            item.put("eventType", e.type());
            item.put("projectPath", e.project());
            item.put("target", e.target());
            item.put("details", e.details());
            eventList.add(item);
        }
        root.put("events", eventList);
        return encode(root, 0) + "\n";
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String encode(Object value, int level) {
        if (value == null) return "null";
        if (value instanceof Instant time) return quoted(time.toString());
        if (value instanceof String str) return quoted(str);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        String ind = "  ".repeat(level), child = "  ".repeat(level + 1);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            List<String> items = new ArrayList<>();
            for (Map.Entry<?,?> e : map.entrySet()) {
                items.add(child + quoted(String.valueOf(e.getKey())) + ": " + encode(e.getValue(), level + 1));
            }
            return "{\n" + String.join(",\n", items) + "\n" + ind + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : iterable) items.add(child + encode(item, level + 1));
            return items.isEmpty() ? "[]" : "[\n" + String.join(",\n", items) + "\n" + ind + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static String quoted(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
