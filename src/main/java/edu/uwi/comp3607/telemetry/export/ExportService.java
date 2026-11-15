package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ExportService {
    private static final DateTimeFormatter SESSION_FOLDER_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final ReportWriter json = new JsonReportWriter();
    private final ReportWriter pdf = new PdfReportWriter();
    private final Path backupRoot;
    private final Map<String, Path> sessionDirectories = new ConcurrentHashMap<>();

    public ExportService() {
        this(Path.of(System.getProperty("user.home"), ".bluej-telemetry", "backups"));
    }

    public ExportService(Path backupRoot) { this.backupRoot = backupRoot; }

    public Path exportJsonAutosave(SessionSnapshot snapshot, Path projectDir) throws IOException {
        Path dir = projectDir.resolve("Telemetry-Report");
        Files.createDirectories(dir);
        Path result = dir.resolve("telemetry-session.json");
        atomicReport(json, snapshot, result);
        Path sessionDir = resolveSessionDir(dir, snapshot);
        Files.createDirectories(sessionDir);
        atomicReport(json, snapshot, sessionDir.resolve("telemetry-session.json"));
        return result;
    }

    public Path exportAll(SessionSnapshot snapshot, Path projectDir) throws IOException {
        Path dir = projectDir.resolve("Telemetry-Report");
        Files.createDirectories(dir);
        Path jsonFile = dir.resolve("telemetry-session.json");
        Path pdfFile = dir.resolve("telemetry-report.pdf");
        atomicReport(json, snapshot, jsonFile);
        atomicReport(pdf, snapshot, pdfFile);
        Path sessionDir = resolveSessionDir(dir, snapshot);
        Files.createDirectories(sessionDir);
        atomicReport(json, snapshot, sessionDir.resolve("telemetry-session.json"));
        atomicReport(pdf, snapshot, sessionDir.resolve("telemetry-report.pdf"));
        String projectName = projectDir.getFileName() == null ? "project" : projectDir.getFileName().toString();
        String safe = projectName.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path backupDir = backupRoot.resolve(snapshot.sessionId).resolve(safe)
                .resolve(Instant.now().toString().replace(':', '-'));
        Files.createDirectories(backupDir);
        Files.copy(jsonFile, backupDir.resolve("telemetry-session.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pdfFile, backupDir.resolve("telemetry-report.pdf"), StandardCopyOption.REPLACE_EXISTING);
        return dir;
    }

    private Path resolveSessionDir(Path reportDir, SessionSnapshot snapshot) {
        String sessionId = snapshot.sessionId == null ? "unknown" : snapshot.sessionId;
        String cacheKey = reportDir.toAbsolutePath().normalize() + "::" + sessionId;
        Path cached = sessionDirectories.get(cacheKey);
        if (cached != null) return cached;

        Instant started = snapshot.startedAtUtc != null ? snapshot.startedAtUtc : Instant.now();
        String baseName = "session-" + SESSION_FOLDER_TIME.format(started);
        Path sessionsRoot = reportDir.resolve("sessions");
        Path candidate = sessionsRoot.resolve(baseName);
        if (Files.exists(candidate) && !belongsToSession(candidate, sessionId)) {
            String suffix = sessionId.replaceAll("[^A-Za-z0-9]", "");
            if (suffix.length() > 8) suffix = suffix.substring(0, 8);
            candidate = sessionsRoot.resolve(baseName + "-" + (suffix.isEmpty() ? "session" : suffix));
        }
        sessionDirectories.put(cacheKey, candidate);
        return candidate;
    }

    private static boolean belongsToSession(Path sessionDir, String sessionId) {
        Path existingJson = sessionDir.resolve("telemetry-session.json");
        if (!Files.isRegularFile(existingJson)) return false;
        try {
            String content = Files.readString(existingJson, StandardCharsets.UTF_8);
            return content.contains("\"sessionId\": \"" + sessionId + "\"");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void atomicReport(ReportWriter writer, SessionSnapshot snapshot, Path path) throws IOException {
        Path tmp = Files.createTempFile(path.getParent(), "telemetry-", ".part");
        try {
            writer.write(snapshot, tmp);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(tmp); }
    }
}
