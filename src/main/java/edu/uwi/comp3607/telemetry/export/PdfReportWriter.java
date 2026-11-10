package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import edu.uwi.comp3607.telemetry.core.TelemetryEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PdfReportWriter implements ReportWriter {
    private static final float W = 595, H = 842;
    private static final String BLUE = "0.10 0.28 0.50", CYAN = "0.08 0.57 0.67", MUTE = "0.35 0.43 0.52";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    @Override public void write(SessionSnapshot s, Path destination) throws IOException {
        Page one = new Page(), two = new Page();
        overview(one, s);
        detail(two, s);
        writePdf(destination, List.of(one.commands.toString(), two.commands.toString()));
    }

    private void header(Page p, String subtitle, int page) {
        p.rect(0, H - 100, W, 100, BLUE);
        p.text(34, 789, 21, true, "BLUEJ TELEMETRY  /  SESSION REPORT", "1 1 1");
        p.text(35, 765, 10, false, subtitle, "0.75 0.91 0.98");
        p.line(34, 40, W - 34, 40, "0.78 0.82 0.86", 0.7f);
        p.text(35, 25, 8, false, "COMP 3607   |   v1.0.3   |   estimates are labelled", MUTE);
        p.text(505, 25, 8, false, "Page " + page + " / 2", MUTE);
    }

    private void overview(Page p, SessionSnapshot s) {
        header(p, "Overview  -  measured events and estimated editor activity", 1);
        p.text(34, 714, 11, true, "SESSION", BLUE);
        p.text(34, 692, 10, false, "Student ID: " + (s.studentId == null ? "NULL" : s.studentId), "0 0 0");
        p.text(34, 675, 9, false, "Started (UTC): " + s.startedAtUtc, MUTE);
        p.text(34, 658, 9, false, "Snapshot (UTC): " + s.snapshotAtUtc, MUTE);
        p.text(34, 640, 9, false, "Session ID: " + s.sessionId, MUTE);

        float y = 594;
        p.text(34, y + 16, 11, true, "ACTIVE TIME VS IDLE TIME  (ESTIMATED)", BLUE);
        p.text(34, y - 4, 9, false, "Active: " + duration(s.activeMillis) + "     Idle: " + duration(s.idleMillis)
                + "     Total: " + duration(s.durationMillis), MUTE);
        float activeFraction = s.durationMillis <= 0 ? 0 : (float) s.activeMillis / s.durationMillis;
        p.rect(34, y - 25, 527, 14, "0.82 0.85 0.88");
        p.rect(34, y - 25, 527 * activeFraction, 14, CYAN);
        p.text(34, y - 41, 8, false, "Quiet spans >= 150 s are classified entirely idle, retroactively.", MUTE);

        y = 503;
        p.text(34, y + 22, 11, true, "COMPILATION RESULTS", BLUE);
        miniStat(p, 34, y - 25, "Attempts", s.compileAttempts);
        miniStat(p, 170, y - 25, "Successful", s.compileSucceeded);
        miniStat(p, 305, y - 25, "Failed", s.compileFailed);
        miniStat(p, 440, y - 25, "First errors", s.compileErrors);
        p.text(34, y - 64, 9, false,
                "Completed compile success rate: " + String.format(java.util.Locale.ROOT, "%.1f%%", s.compileSuccessRatePercent)
                + "    |    First warnings: " + s.compileWarnings, MUTE);

        y = 404;
        p.text(34, y + 19, 11, true, "COMPILATION ERROR FREQUENCY  (DERIVED CATEGORIES)", BLUE);
        bars(p, s.errorBreakdown, 34, y - 8, 510, 19, 4);

        y = 275;
        p.text(34, y + 20, 11, true, "EDIT FREQUENCY  (CHANGES DETECTED PER FIVE MINUTES)", BLUE);
        bars(p, s.editsPerFiveMinuteWindow, 34, y - 6, 510, 19, 4);

        p.text(34, 145, 11, true, "EXECUTION OUTCOMES", BLUE);
        p.text(34, 124, 9, false, outcomeLine(s.executionOutcomes), "0 0 0");
        p.text(34, 105, 9, false, "Estimated positive net words: " + s.estimatedNewWords +
                "    |    Estimated words / active min: " + String.format(java.util.Locale.ROOT, "%.1f", s.estimatedWordsPerActiveMinute), MUTE);
        p.text(34, 86, 8, false, "Console output, stack traces, same-length edits and exact inserted words are unavailable.", MUTE);
    }

    private void detail(Page p, SessionSnapshot s) {
        header(p, "Event timeline  -  observed project, class and compiled method-count activity", 2);
        p.text(34, 710, 11, true, "PROJECT / CLASS / METHOD TIMELINE", BLUE);
        p.text(34, 694, 8, false, "Times UTC. Method numbers are compiled-class snapshots, not live source parsing.", MUTE);
        List<TelemetryEvent> timeline = s.events.stream().filter(e ->
                e.type().startsWith("PROJECT_") || e.type().startsWith("PACKAGE_") ||
                e.type().startsWith("CLASS_") || e.type().equals("METHODS_SAMPLED")
        ).sorted(Comparator.comparing(TelemetryEvent::timestamp)).toList();
        float y = 672;
        p.line(48, 657, 48, 340, "0.75 0.82 0.88", 2);
        int displayed = 0;
        for (TelemetryEvent e : timeline) {
            if (displayed >= 15) break;
            String detail = pretty(e);
            p.circle(48, y - 1, 3.6f, e.type().equals("METHODS_SAMPLED") ? CYAN : BLUE);
            p.text(62, y - 3, 8, true, TIME.format(e.timestamp()), BLUE);
            p.text(116, y - 3, 8, false, truncate(detail, 94), "0.12 0.17 0.23");
            y -= 21;
            displayed++;
        }
        if (displayed == 0) p.text(62, 655, 10, false, "No class or project lifecycle events observed.", MUTE);
        if (timeline.size() > displayed) {
            p.text(62, 341, 8, false, "+ " + (timeline.size() - displayed) + " more events in JSON export", MUTE);
        }

        p.text(34, 301, 11, true, "FIRST AVAILABLE COMPILATION ERRORS", BLUE);
        p.rect(34, 274, 527, 20, "0.90 0.94 0.97");
        p.text(40, 280, 8, true, "TIME UTC", BLUE);
        p.text(114, 280, 8, true, "FILE / LINE", BLUE);
        p.text(255, 280, 8, true, "ERROR CATEGORY + MESSAGE", BLUE);
        List<TelemetryEvent> errors = s.events.stream().filter(e -> e.type().equals("COMPILE_ERROR")).toList();
        y = 258;
        int count = 0;
        for (TelemetryEvent e : errors) {
            if (count++ >= 8) break;
            String loc = String.valueOf(e.target()) + ":" + String.valueOf(e.details().getOrDefault("line", "?"));
            String msg = e.details().get("errorCode") + "  " + e.details().get("message");
            p.text(40, y, 8, false, TIME.format(e.timestamp()), "0 0 0");
            p.text(114, y, 8, false, truncate(loc, 30), "0 0 0");
            p.text(255, y, 8, false, truncate(msg, 71), "0 0 0");
            p.line(34, y - 7, 561, y - 7, "0.90 0.92 0.94", 0.5f);
            y -= 22;
        }
        if (errors.isEmpty()) p.text(40, 256, 9, false, "No errors reported during observed user-triggered compilations.", MUTE);
        p.text(34, 64, 8, false,
                "Error codes are derived categories; detailed source snippets and all diagnostics are not recorded.", MUTE);
    }

    private static String pretty(TelemetryEvent e) {
        String type = e.type().replace('_', ' ');
        String target = e.target() == null ? "" : e.target();
        if (type.startsWith("PROJECT")) {
            int sep = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
            if (sep >= 0) target = target.substring(sep + 1);
        }
        Object methods = e.details().get("declaredMethodCount");
        if (methods != null) return "METHODS SAMPLED  " + target + "  (declared: " + methods + ")";
        return type + "  " + target;
    }
    private static String duration(long millis) {
        long sec = millis / 1000;
        return (sec / 3600) + "h " + ((sec / 60) % 60) + "m " + (sec % 60) + "s";
    }
    private static String outcomeLine(Map<String,Integer> map) {
        return "Normal: " + map.getOrDefault("NORMAL", 0) + "    Exception: " + map.getOrDefault("EXCEPTION", 0)
                + "    Terminated: " + map.getOrDefault("TERMINATED", 0)
                + "    Unknown: " + map.getOrDefault("UNKNOWN", 0);
    }
    private static void miniStat(Page p, float x, float y, String label, int amount) {
        p.rect(x, y - 12, 120, 42, "0.92 0.96 0.98");
        p.text(x + 9, y + 8, 18, true, Integer.toString(amount), BLUE);
        p.text(x + 9, y - 5, 8, false, label, MUTE);
    }
    private static void bars(Page p, Map<String,Integer> data, float x, float top,
                             float width, int lineHeight, int maxRows) {
        if (data.isEmpty()) { p.text(x, top - 2, 9, false, "No detected events in this category.", MUTE); return; }
        int max = data.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int pos = 0;
        for (Map.Entry<String,Integer> entry : data.entrySet()) {
            if (pos >= maxRows) break;
            float y = top - (pos * lineHeight);
            String key = entry.getKey();
            if (key.matches("\\d+")) key = "Minute " + (Integer.parseInt(key) * 5) + "-" + (Integer.parseInt(key) * 5 + 5);
            p.text(x, y, 8, false, truncate(key, 28), "0.15 0.18 0.22");
            float barX = x + 160, len = (width - 212) * entry.getValue() / Math.max(1,max);
            p.rect(barX, y - 1, len, 9, CYAN);
            p.text(x + width - 35, y, 8, true, String.valueOf(entry.getValue()), BLUE);
            pos++;
        }
        if (data.size() > maxRows) p.text(x, top - maxRows * lineHeight, 8, false, "+ more in JSON", MUTE);
    }
    private static String truncate(String str, int length) {
        if (str == null) return "";
        return str.length() > length ? str.substring(0, length - 3) + "..." : str;
    }

    private static final class Page {
        final StringBuilder commands = new StringBuilder();
        void text(float x, float y, int size, boolean bold, String value, String rgb) {
            commands.append(rgb).append(" rg BT /F").append(bold ? '2' : '1').append(' ')
                    .append(size).append(" Tf ").append(x).append(' ').append(y)
                    .append(" Td (").append(escaped(value)).append(") Tj ET\n");
        }
        void rect(float x, float y, float w, float h, String color) {
            commands.append(color).append(" rg ").append(x).append(' ').append(y).append(' ')
                    .append(w).append(' ').append(h).append(" re f\n");
        }
        void line(float x, float y, float x2, float y2, String color, float width) {
            commands.append(color).append(" RG ").append(width).append(" w ").append(x).append(' ')
                    .append(y).append(" m ").append(x2).append(' ').append(y2).append(" l S\n");
        }
        void circle(float x, float y, float radius, String color) {
            // Four cubic Beziers approximate a circular timeline marker.
            float k = radius * 0.55228475f;
            commands.append(color).append(" rg ").append(x + radius).append(' ').append(y).append(" m ")
                    .append(x + radius).append(' ').append(y+k).append(' ').append(x+k).append(' ').append(y+radius)
                    .append(' ').append(x).append(' ').append(y+radius).append(" c ")
                    .append(x-k).append(' ').append(y+radius).append(' ').append(x-radius).append(' ').append(y+k)
                    .append(' ').append(x-radius).append(' ').append(y).append(" c ")
                    .append(x-radius).append(' ').append(y-k).append(' ').append(x-k).append(' ').append(y-radius)
                    .append(' ').append(x).append(' ').append(y-radius).append(" c ")
                    .append(x+k).append(' ').append(y-radius).append(' ').append(x+radius).append(' ').append(y-k)
                    .append(' ').append(x+radius).append(' ').append(y).append(" c f\n");
        }
        private static String escaped(String text) {
            if (text == null) return "";
            StringBuilder b = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c == '(' || c == ')' || c == '\\') b.append('\\');
                b.append(c >= 32 && c <= 126 ? c : '-');
            }
            return b.toString();
        }
    }

    private static void writePdf(Path path, List<String> content) throws IOException {
        // PDF 1.4 with Helvetica fonts, two page objects and UTF-8-safe ASCII streams.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(output, "%PDF-1.4\n");
        String pages = "5 0 R 7 0 R";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [" + pages + "] /Count 2 >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents 6 0 R >>",
                stream(content.get(0)),
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents 8 0 R >>",
                stream(content.get(1))
        );
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(output.size());
            write(output, (i+1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = output.size();
        write(output, "xref\n0 " + (objects.size()+1) + "\n0000000000 65535 f \n");
        for (int at : offsets) write(output, String.format(java.util.Locale.ROOT, "%010d 00000 n \n", at));
        write(output, "trailer\n<< /Size " + (objects.size()+1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        Files.write(path, output.toByteArray());
    }
    private static String stream(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        return "<< /Length " + bytes.length + " >>\nstream\n" + value + "endstream";
    }
    private static void write(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
}
