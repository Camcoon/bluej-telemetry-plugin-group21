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

public final class JsonReportWriter implements ReportWriter {
    @Override public void write(SessionSnapshot s, Path path) throws IOException {
        Files.writeString(path, toJson(s), StandardCharsets.UTF_8);
    }

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

    private double round(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object parsed = new StrictJsonParser(json).parseRoot();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Root JSON value must be an object");
        }
        return (Map<String, Object>) map;
    }

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

    private static void requireKeys(Map<String, Object> map, List<String> keys) {
        for (String key : keys) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException("Missing required JSON key: " + key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object at " + path);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireList(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array at " + path);
        }
        return (List<Object>) list;
    }

    private static String requireString(Object value, String path) {
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException("Expected JSON string at " + path);
        }
        return str;
    }

    private static long requireIntegral(Object value, String path) {
        if (!(value instanceof Long l)) {
            throw new IllegalArgumentException("Expected integral JSON number at " + path);
        }
        return l;
    }

    private static double requireNumber(Object value, String path) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("Expected JSON number at " + path);
        }
        return n.doubleValue();
    }

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

    private static final class StrictJsonParser {
        private final String src;
        private int pos;

        StrictJsonParser(String src) {
            this.src = Objects.requireNonNull(src, "json string must not be null");
        }

        Object parseRoot() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != src.length()) {
                throw new IllegalArgumentException("Unexpected trailing characters at position " + pos);
            }
            return value;
        }

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

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at position " + pos);
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }

        private void expect(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }
}
