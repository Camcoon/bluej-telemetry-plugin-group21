package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import edu.uwi.comp3607.telemetry.core.TelemetryEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes session snapshots to JSON and validates/parses the generated JSON.
 *
 * <p>The writer is hand-rolled rather than depending on a JSON library. It
 * emits a fixed schema with a {@code sessionMetadata}, {@code compilationSummary},
 * {@code editorActivityEstimated}, {@code executionOutcomeSummary} and {@code events}
 * section. Values are written through a recursive encoder that supports maps,
 * iterables, strings, numbers, booleans, enums, {@link Path} and {@link Instant};
 * finite doubles are rounded to two decimal places for readability.</p>
 *
 * <p>For testing and round-trip verification, {@link #parseObject(String)} and
 * {@link #validateSchema(String, SessionSnapshot)} use the strict
 * {@link StrictJsonParser} implementation below. Validation checks required
 * keys, types, UTC timestamp format and consistency between summary counts and
 * the exported event list. The class is stateless and safe to share between
 * threads.</p>
 */
public final class JsonReportWriter implements ReportWriter {
    /**
     * Serializes a snapshot to a UTF-8 JSON file.
     *
     * @param s    the snapshot to serialize
     * @param path the destination file
     * @throws IOException if the file cannot be written
     */
    @Override public void write(SessionSnapshot s, Path path) throws IOException {
        Files.writeString(path, toJson(s), StandardCharsets.UTF_8);
    }

    /**
     * Builds the complete JSON document for a snapshot.
     *
     * @param s the snapshot to serialize; must not be {@code null}
     * @return the JSON text, terminated by a newline
     * @throws NullPointerException if {@code s} is {@code null}
     */
    public String toJson(SessionSnapshot s) {
        Objects.requireNonNull(s, "snapshot must not be null");
        int firstErrors = countFirstDiagnostics(s, "COMPILE_ERROR");
        int firstWarnings = countFirstDiagnostics(s, "COMPILE_WARNING");
        Map<String,Object> root = new LinkedHashMap<>();
        Map<String,Object> meta = new LinkedHashMap<>();
        meta.put("schemaVersion", s.version);
        meta.put("sessionId", s.sessionId);
        meta.put("studentId", s.studentId);
        meta.put("projectPath", s.projectPath);
        meta.put("projectPaths", s.projectPaths);
        meta.put("startedAtUtc", s.startedAtUtc);
        meta.put("snapshotAtUtc", s.snapshotAtUtc);
        meta.put("endedAtUtc", s.endedAtUtc);
        meta.put("totalDurationMillis", s.durationMillis);
        meta.put("activeCodingTimeMillisEstimated", s.activeMillis);
        meta.put("idleTimeMillisEstimated", s.idleMillis);
        meta.put("totalCompilationAttempts", s.compileAttempts);
        root.put("sessionMetadata", meta);
        Map<String,Object> compilations = new LinkedHashMap<>();
        compilations.put("totalAttempts", s.compileAttempts);
        compilations.put("successful", s.compileSucceeded);
        compilations.put("failed", s.compileFailed);
        compilations.put("totalErrorDiagnostics", s.compileErrors);
        compilations.put("totalWarningDiagnostics", s.compileWarnings);
        compilations.put("firstErrorsRecorded", firstErrors);
        compilations.put("firstWarningsRecorded", firstWarnings);
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
        editor.put("limitations", List.of(
                "Content fingerprints detect visible same-length edits between poll snapshots; exact keystrokes are not recorded",
                "Positive net word counts are not a count of exact inserted words",
                "Idle intervals are classified retroactively after 150 seconds"));
        root.put("editorActivityEstimated", editor);
        root.put("executionOutcomeSummary", s.executionOutcomes);
        List<Object> eventList = new ArrayList<>();
        for (TelemetryEvent e : s.events) {
            if (e == null) continue;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("timestampUtc", e.timestamp());
            item.put("eventType", e.type());
            item.put("projectPath", e.project());
            item.put("target", e.target());
            item.put("details", e.details() == null ? Map.of() : e.details());
            eventList.add(item);
        }
        root.put("events", eventList);
        return encode(root, 0) + "\n";
    }

    /**
     * Counts diagnostics flagged as first in their attempt, deriving the value
     * from events unless there are no events at all, in which case the summary
     * value stored in the snapshot is used.
     *
     * @param s         the snapshot being serialized
     * @param eventType either {@code COMPILE_ERROR} or {@code COMPILE_WARNING}
     * @return the number of first-in-attempt diagnostics of that type
     */
    private static int countFirstDiagnostics(SessionSnapshot s, String eventType) {
        if (s.events.isEmpty()) {
            if ("COMPILE_ERROR".equals(eventType)) return s.firstCompileErrors;
            if ("COMPILE_WARNING".equals(eventType)) return s.firstCompileWarnings;
        }
        int count = 0;
        for (TelemetryEvent e : s.events) {
            if (e != null && eventType.equals(e.type()) && !Boolean.FALSE.equals(e.details().get("firstInAttempt"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rounds a metric to two decimal places, mapping non-finite values to zero
     * so that the generated JSON is always standards compliant.
     *
     * @param v the value to round
     * @return the rounded value
     */
    private double round(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Recursively encodes a Java value as pretty-printed JSON.
     *
     * <p>Nested maps and iterables are indented by two spaces per level and
     * retain their iteration order. Null, strings, booleans, numbers, enums,
     * paths and timestamps have dedicated handling; any other type is
     * rejected.</p>
     *
     * @param value the value to encode
     * @param level the current indentation level
     * @return the JSON representation
     * @throws IllegalArgumentException if the value type is not supported
     */
    private static String encode(Object value, int level) {
        if (value == null) return "null";
        if (value instanceof Instant time) return quoted(DateTimeFormatter.ISO_INSTANT.format(time));
        if (value instanceof Path path) return quoted(path.toString());
        if (value instanceof Enum<?> en) return quoted(en.name());
        if (value instanceof String str) return quoted(str);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Double d) return Double.isFinite(d) ? d.toString() : "0.0";
        if (value instanceof Float f) return Float.isFinite(f) ? f.toString() : "0.0";
        if (value instanceof Number num) return num.toString();
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

    /**
     * Quotes a string and escapes control characters, quotes, backslashes and
     * surrogates using JSON escape sequences.
     *
     * @param s the raw string
     * @return the quoted, escaped string literal
     */
    private static String quoted(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || (c >= 0x7f && c <= 0x9f) || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Parses a JSON document whose root value must be an object.
     *
     * @param json the JSON text
     * @return the parsed root object with insertion-ordered keys
     * @throws IllegalArgumentException if the input is malformed or the root is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object parsed = new StrictJsonParser(json).parseRoot();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Root JSON value must be an object");
        }
        return (Map<String, Object>) map;
    }

    /**
     * Validates a generated JSON report against the expected schema and,
     * optionally, against the snapshot it was produced from.
     *
     * <p>The validator first checks that all required sections and fields are
     * present with the correct types and that timestamps are ISO-8601 UTC
     * values ending in {@code Z}. It then cross-checks timing totals,
     * compilation summary counts, derived error categories and the exported
     * event list. When {@code expected} is supplied, individual event
     * timestamps, types, projects, targets and details are compared as well.</p>
     *
     * @param json     the JSON text to validate
     * @param expected the snapshot to compare against, or {@code null} for schema-only validation
     * @throws IllegalArgumentException if the JSON is malformed, incomplete, inconsistent
     *                                  or does not match the expected snapshot
     */
    @SuppressWarnings("unchecked")
    public static void validateSchema(String json, SessionSnapshot expected) {
        Map<String, Object> root = parseObject(json);
        requireKeys(root, List.of(
                "sessionMetadata",
                "compilationSummary",
                "editorActivityEstimated",
                "executionOutcomeSummary",
                "events"));

        Map<String, Object> meta = requireMap(root.get("sessionMetadata"), "sessionMetadata");
        requireKeys(meta, List.of(
                "schemaVersion",
                "sessionId",
                "studentId",
                "projectPath",
                "projectPaths",
                "startedAtUtc",
                "snapshotAtUtc",
                "endedAtUtc",
                "totalDurationMillis",
                "activeCodingTimeMillisEstimated",
                "idleTimeMillisEstimated",
                "totalCompilationAttempts"));

        String schemaVersion = requireString(meta.get("schemaVersion"), "sessionMetadata.schemaVersion");
        String sessionId = requireString(meta.get("sessionId"), "sessionMetadata.sessionId");
        Object studentId = meta.get("studentId");
        if (studentId != null && !(studentId instanceof String)) {
            throw new IllegalArgumentException("sessionMetadata.studentId must be String or null");
        }
        Object projectPath = meta.get("projectPath");
        if (projectPath != null && !(projectPath instanceof String)) {
            throw new IllegalArgumentException("sessionMetadata.projectPath must be String or null");
        }
        List<Object> projectPaths = requireList(meta.get("projectPaths"), "sessionMetadata.projectPaths");
        for (Object p : projectPaths) {
            requireString(p, "sessionMetadata.projectPaths[]");
        }

        Instant startedAt = requireUtcTimestamp(meta.get("startedAtUtc"), "sessionMetadata.startedAtUtc");
        Instant snapshotAt = requireUtcTimestamp(meta.get("snapshotAtUtc"), "sessionMetadata.snapshotAtUtc");
        Instant endedAt = meta.get("endedAtUtc") == null
                ? null
                : requireUtcTimestamp(meta.get("endedAtUtc"), "sessionMetadata.endedAtUtc");

        long totalDuration = requireIntegral(meta.get("totalDurationMillis"), "sessionMetadata.totalDurationMillis");
        long activeMillis = requireIntegral(meta.get("activeCodingTimeMillisEstimated"), "sessionMetadata.activeCodingTimeMillisEstimated");
        long idleMillis = requireIntegral(meta.get("idleTimeMillisEstimated"), "sessionMetadata.idleTimeMillisEstimated");
        long metaAttempts = requireIntegral(meta.get("totalCompilationAttempts"), "sessionMetadata.totalCompilationAttempts");
        if (activeMillis + idleMillis != totalDuration) {
            throw new IllegalArgumentException("activeCodingTimeMillisEstimated + idleTimeMillisEstimated must equal totalDurationMillis");
        }

        Map<String, Object> comp = requireMap(root.get("compilationSummary"), "compilationSummary");
        requireKeys(comp, List.of(
                "totalAttempts",
                "successful",
                "failed",
                "totalErrorDiagnostics",
                "totalWarningDiagnostics",
                "firstErrorsRecorded",
                "firstWarningsRecorded",
                "successRatePercent",
                "errorCategoriesDerived"));

        long compAttempts = requireIntegral(comp.get("totalAttempts"), "compilationSummary.totalAttempts");
        long compSucceeded = requireIntegral(comp.get("successful"), "compilationSummary.successful");
        long compFailed = requireIntegral(comp.get("failed"), "compilationSummary.failed");
        long compErrors = requireIntegral(comp.get("totalErrorDiagnostics"), "compilationSummary.totalErrorDiagnostics");
        long compWarnings = requireIntegral(comp.get("totalWarningDiagnostics"), "compilationSummary.totalWarningDiagnostics");
        long firstErrors = requireIntegral(comp.get("firstErrorsRecorded"), "compilationSummary.firstErrorsRecorded");
        long firstWarnings = requireIntegral(comp.get("firstWarningsRecorded"), "compilationSummary.firstWarningsRecorded");
        double successRate = requireNumber(comp.get("successRatePercent"), "compilationSummary.successRatePercent");
        Map<String, Object> categories = requireMap(comp.get("errorCategoriesDerived"), "compilationSummary.errorCategoriesDerived");

        if (metaAttempts != compAttempts) {
            throw new IllegalArgumentException("sessionMetadata.totalCompilationAttempts must match compilationSummary.totalAttempts");
        }
        long categorySum = 0;
        for (Map.Entry<String, Object> entry : categories.entrySet()) {
            categorySum += requireIntegral(entry.getValue(), "compilationSummary.errorCategoriesDerived." + entry.getKey());
        }

        Map<String, Object> editor = requireMap(root.get("editorActivityEstimated"), "editorActivityEstimated");
        requireKeys(editor, List.of(
                "pollIntervalSeconds",
                "wordSampleIntervalSeconds",
                "idleThresholdSeconds",
                "editFrequencyByFiveMinuteWindow",
                "estimatedPositiveNetNewWords",
                "estimatedWordsPerActiveMinute",
                "limitations"));
        requireIntegral(editor.get("pollIntervalSeconds"), "editorActivityEstimated.pollIntervalSeconds");
        requireIntegral(editor.get("wordSampleIntervalSeconds"), "editorActivityEstimated.wordSampleIntervalSeconds");
        requireIntegral(editor.get("idleThresholdSeconds"), "editorActivityEstimated.idleThresholdSeconds");
        Map<String, Object> editWindows = requireMap(editor.get("editFrequencyByFiveMinuteWindow"), "editorActivityEstimated.editFrequencyByFiveMinuteWindow");
        for (Map.Entry<String, Object> entry : editWindows.entrySet()) {
            requireIntegral(entry.getValue(), "editorActivityEstimated.editFrequencyByFiveMinuteWindow." + entry.getKey());
        }
        long netNewWords = requireIntegral(editor.get("estimatedPositiveNetNewWords"), "editorActivityEstimated.estimatedPositiveNetNewWords");
        double cadence = requireNumber(editor.get("estimatedWordsPerActiveMinute"), "editorActivityEstimated.estimatedWordsPerActiveMinute");
        List<Object> limitations = requireList(editor.get("limitations"), "editorActivityEstimated.limitations");
        for (Object lim : limitations) {
            requireString(lim, "editorActivityEstimated.limitations[]");
        }

        Map<String, Object> outcomes = requireMap(root.get("executionOutcomeSummary"), "executionOutcomeSummary");
        for (Map.Entry<String, Object> entry : outcomes.entrySet()) {
            requireIntegral(entry.getValue(), "executionOutcomeSummary." + entry.getKey());
        }

        List<Object> events = requireList(root.get("events"), "events");
        long eventCompileStarted = 0;
        long eventCompileSucceeded = 0;
        long eventCompileFailed = 0;
        long eventCompileErrors = 0;
        long eventCompileWarnings = 0;
        long eventFirstErrors = 0;
        long eventFirstWarnings = 0;

        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> item = requireMap(events.get(i), "events[" + i + "]");
            requireKeys(item, List.of("timestampUtc", "eventType", "projectPath", "target", "details"));
            Instant evTime = requireUtcTimestamp(item.get("timestampUtc"), "events[" + i + "].timestampUtc");
            String evType = requireString(item.get("eventType"), "events[" + i + "].eventType");
            Object evProject = item.get("projectPath");
            if (evProject != null && !(evProject instanceof String)) {
                throw new IllegalArgumentException("events[" + i + "].projectPath must be String or null");
            }
            Object evTarget = item.get("target");
            if (evTarget != null && !(evTarget instanceof String)) {
                throw new IllegalArgumentException("events[" + i + "].target must be String or null");
            }
            Map<String, Object> evDetails = requireMap(item.get("details"), "events[" + i + "].details");

            switch (evType) {
                case "COMPILE_STARTED" -> eventCompileStarted++;
                case "COMPILE_SUCCEEDED" -> eventCompileSucceeded++;
                case "COMPILE_FAILED" -> eventCompileFailed++;
                case "COMPILE_ERROR" -> {
                    eventCompileErrors++;
                    if (!Boolean.FALSE.equals(evDetails.get("firstInAttempt"))) eventFirstErrors++;
                }
                case "COMPILE_WARNING" -> {
                    eventCompileWarnings++;
                    if (!Boolean.FALSE.equals(evDetails.get("firstInAttempt"))) eventFirstWarnings++;
                }
                default -> { }
            }

            if (expected != null && i < expected.events.size()) {
                TelemetryEvent exp = expected.events.get(i);
                if (!evTime.equals(exp.timestamp())) {
                    throw new IllegalArgumentException("Mismatch at events[" + i + "].timestampUtc");
                }
                if (!evType.equals(exp.type())) {
                    throw new IllegalArgumentException("Mismatch at events[" + i + "].eventType");
                }
                if (!Objects.equals(evProject, exp.project())) {
                    throw new IllegalArgumentException("Mismatch at events[" + i + "].projectPath");
                }
                if (!Objects.equals(evTarget, exp.target())) {
                    throw new IllegalArgumentException("Mismatch at events[" + i + "].target");
                }
                for (Map.Entry<String, Object> d : exp.details().entrySet()) {
                    Object actualVal = evDetails.get(d.getKey());
                    Object expectedVal = d.getValue();
                    if (expectedVal instanceof Number expNum && actualVal instanceof Number actNum) {
                        if (Double.compare(expNum.doubleValue(), actNum.doubleValue()) != 0) {
                            throw new IllegalArgumentException("Mismatch at events[" + i + "].details." + d.getKey());
                        }
                    } else if (expectedVal instanceof Instant expInst) {
                        if (!Objects.equals(DateTimeFormatter.ISO_INSTANT.format(expInst), actualVal)) {
                            throw new IllegalArgumentException("Mismatch at events[" + i + "].details." + d.getKey());
                        }
                    } else if (!Objects.equals(expectedVal, actualVal)) {
                        throw new IllegalArgumentException("Mismatch at events[" + i + "].details." + d.getKey());
                    }
                }
            }
        }

        if (!events.isEmpty() && (eventCompileStarted > 0 || eventCompileSucceeded > 0 || eventCompileFailed > 0
                || eventCompileErrors > 0 || eventCompileWarnings > 0)) {
            if (compAttempts != eventCompileStarted
                    || compSucceeded != eventCompileSucceeded
                    || compFailed != eventCompileFailed
                    || compErrors != eventCompileErrors
                    || compWarnings != eventCompileWarnings
                    || firstErrors != eventFirstErrors
                    || firstWarnings != eventFirstWarnings
                    || categorySum != compErrors) {
                throw new IllegalArgumentException("Compilation summary counts do not match exported compilation events");
            }
        }

        if (expected != null) {
            if (!Objects.equals(expected.version, schemaVersion)
                    || !Objects.equals(expected.sessionId, sessionId)
                    || !Objects.equals(expected.studentId, studentId)
                    || !Objects.equals(expected.projectPath, projectPath)
                    || !Objects.equals(expected.startedAtUtc, startedAt)
                    || !Objects.equals(expected.snapshotAtUtc, snapshotAt)
                    || !Objects.equals(expected.endedAtUtc, endedAt)
                    || expected.durationMillis != totalDuration
                    || expected.activeMillis != activeMillis
                    || expected.idleMillis != idleMillis
                    || expected.compileAttempts != compAttempts
                    || expected.compileSucceeded != compSucceeded
                    || expected.compileFailed != compFailed
                    || expected.compileErrors != compErrors
                    || expected.compileWarnings != compWarnings
                    || expected.estimatedNewWords != netNewWords
                    || Math.abs(Math.round(expected.compileSuccessRatePercent * 100.0) / 100.0 - successRate) > 1e-6
                    || Math.abs(Math.round(expected.estimatedWordsPerActiveMinute * 100.0) / 100.0 - cadence) > 1e-6
                    || expected.events.size() != events.size()) {
                throw new IllegalArgumentException("Parsed JSON values do not match expected SessionSnapshot");
            }
        }
    }

    /**
     * Ensures that every required key is present in an object.
     *
     * @param map  the object to inspect
     * @param keys the required keys
     * @throws IllegalArgumentException if a key is missing
     */
    private static void requireKeys(Map<String, Object> map, List<String> keys) {
        for (String key : keys) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException("Missing required JSON key: " + key);
            }
        }
    }

    /**
     * Casts a parsed value to a JSON object.
     *
     * @param value the parsed value
     * @param path  the path used in error messages
     * @return the value as a map
     * @throws IllegalArgumentException if the value is not an object
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object at " + path);
        }
        return (Map<String, Object>) map;
    }

    /**
     * Casts a parsed value to a JSON array.
     *
     * @param value the parsed value
     * @param path  the path used in error messages
     * @return the value as a list
     * @throws IllegalArgumentException if the value is not an array
     */
    @SuppressWarnings("unchecked")
    private static List<Object> requireList(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array at " + path);
        }
        return (List<Object>) list;
    }

    /**
     * Requires a parsed value to be a JSON string.
     *
     * @param value the parsed value
     * @param path  the path used in error messages
     * @return the string value
     * @throws IllegalArgumentException if the value is not a string
     */
    private static String requireString(Object value, String path) {
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException("Expected JSON string at " + path);
        }
        return str;
    }

    /**
     * Requires a parsed value to be an integral JSON number, represented as
     * {@link Long} by {@link StrictJsonParser}.
     *
     * @param value the parsed value
     * @param path  the path used in error messages
     * @return the integral value
     * @throws IllegalArgumentException if the value is not a long
     */
    private static long requireIntegral(Object value, String path) {
        if (!(value instanceof Long l)) {
            throw new IllegalArgumentException("Expected integral JSON number at " + path);
        }
        return l;
    }

    /**
     * Requires a parsed value to be a JSON number.
     *
     * @param value the parsed value
     * @param path  the path used in error messages
     * @return the numeric value as a double
     * @throws IllegalArgumentException if the value is not a number
     */
    private static double requireNumber(Object value, String path) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("Expected JSON number at " + path);
        }
        return n.doubleValue();
    }

    /**
     * Requires a UTC ISO-8601 timestamp string ending in {@code Z}.
     *
     * @param value the parsed value
     * @param path  the path used in error messages
     * @return the parsed instant
     * @throws IllegalArgumentException if the value is not a UTC timestamp
     */
    private static Instant requireUtcTimestamp(Object value, String path) {
        String text = requireString(value, path);
        if (!text.endsWith("Z")) {
            throw new IllegalArgumentException("Timestamp at " + path + " must be in UTC (ending with 'Z'): " + text);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid UTC timestamp at " + path + ": " + text, ex);
        }
    }

    /**
     * Minimal strict recursive-descent JSON parser used for report validation.
     *
     * <p>Only JSON whitespace is skipped, duplicate object keys overwrite
     * earlier values, integral numbers become {@link Long} and fractional or
     * exponent numbers become {@link Double}. Any trailing content or invalid
     * syntax is rejected with an exception carrying the offending position.</p>
     *
     * <p>A new parser instance is created per document, so instances are not
     * shared between threads.</p>
     */
    private static final class StrictJsonParser {
        /** The complete JSON source text. */
        private final String src;

        /** Current read cursor within {@link #src}. */
        private int pos;

        /**
         * Creates a parser for the given JSON text.
         *
         * @param src the JSON source; must not be {@code null}
         */
        StrictJsonParser(String src) {
            this.src = Objects.requireNonNull(src, "json string must not be null");
        }

        /**
         * Parses the whole document and requires the cursor to reach the end.
         *
         * @return the parsed root value
         * @throws IllegalArgumentException if the input is malformed or has trailing characters
         */
        Object parseRoot() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != src.length()) {
                throw new IllegalArgumentException("Unexpected trailing characters at position " + pos);
            }
            return value;
        }

        /**
         * Dispatches to the parser for the value beginning at the cursor.
         *
         * @return the parsed value
         * @throws IllegalArgumentException on an unexpected character or end of input
         */
        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
                }
            };
        }

        /**
         * Parses a JSON object into an insertion-ordered map.
         *
         * @return the parsed object
         * @throws IllegalArgumentException on malformed keys, values or separators
         */
        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' in object at position " + pos);
                }
            }
            return map;
        }

        /**
         * Parses a JSON array into a list.
         *
         * @return the parsed array
         * @throws IllegalArgumentException on malformed values or separators
         */
        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' in array at position " + pos);
                }
            }
            return list;
        }

        /**
         * Parses a quoted string, decoding standard escapes and {@code \\uXXXX}
         * sequences, and rejecting unescaped control characters.
         *
         * @return the decoded string
         * @throws IllegalArgumentException on unterminated strings or invalid escapes
         */
        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 32) {
                    throw new IllegalArgumentException("Unescaped control character in string at position " + (pos - 1));
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence at end of input");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Incomplete unicode escape at position " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "' at position " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        /**
         * Parses a JSON number, returning {@link Long} for integers and
         * {@link Double} for numbers with a fraction or exponent.
         *
         * @return the parsed number
         * @throws IllegalArgumentException on an invalid number token
         */
        private Number parseNumber() {
            int start = pos;
            if (peek('-')) pos++;
            if (pos >= src.length()) throw new IllegalArgumentException("Invalid number at position " + start);
            if (src.charAt(pos) == '0') {
                pos++;
            } else if (src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            } else {
                throw new IllegalArgumentException("Invalid number at position " + start);
            }
            boolean floating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                floating = true;
                pos++;
                if (pos >= src.length() || !Character.isDigit(src.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid fractional number at position " + start);
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                if (pos >= src.length() || !Character.isDigit(src.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid exponent in number at position " + start);
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String token = src.substring(start, pos);
            if (floating) return Double.valueOf(token);
            return Long.valueOf(token);
        }

        /**
         * Parses the {@code true} or {@code false} literal.
         *
         * @return the parsed boolean
         * @throws IllegalArgumentException if neither literal is present
         */
        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean literal at position " + pos);
        }

        /**
         * Parses the {@code null} literal.
         *
         * @return {@code null}
         * @throws IllegalArgumentException if the literal is absent
         */
        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at position " + pos);
        }

        /** Advances the cursor past any JSON whitespace characters. */
        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        /**
         * Tests the next character without consuming it.
         *
         * @param c the expected character
         * @return {@code true} if the cursor is in range and the character matches
         */
        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        /**
         * Consumes the expected character.
         *
         * @param expected the character that must appear next
         * @throws IllegalArgumentException if the cursor is at the end or the character differs
         */
        private void expect(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }
}
