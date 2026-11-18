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

/**
 * Coordinates writing JSON and PDF reports into project and backup locations.
 *
 * <p>Every report is first written to a temporary sibling file and then moved
 * into place, so a failed write cannot leave a partially written report at the
 * destination. JSON autosaves are written both to {@code Telemetry-Report} in
 * the project and to a per-session subfolder; full exports additionally write
 * a PDF and copy both files into a timestamped backup directory under the
 * user's home directory.</p>
 *
 * <p>The session-directory cache is concurrent, so instances may be shared by
 * multiple worker threads.</p>
 */
public final class ExportService {
    /** Formatter for session folder names, using UTC to avoid local-time ambiguity. */
    private static final DateTimeFormatter SESSION_FOLDER_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    /** Writer used for JSON reports. */
    private final ReportWriter json = new JsonReportWriter();

    /** Writer used for two-page PDF reports. */
    private final ReportWriter pdf = new PdfReportWriter();

    /** Root directory for timestamped backup copies of full exports. */
    private final Path backupRoot;

    /** Cache mapping report directory plus session id to a stable session folder. */
    private final Map<String, Path> sessionDirectories = new ConcurrentHashMap<>();

    /**
     * Creates a service that stores backups under
     * {@code ~/.bluej-telemetry/backups}.
     */
    public ExportService() {
        this(Path.of(System.getProperty("user.home"), ".bluej-telemetry", "backups"));
    }

    /**
     * Creates a service with an explicit backup root.
     *
     * @param backupRoot the directory under which backup copies are stored
     */
    public ExportService(Path backupRoot) { this.backupRoot = backupRoot; }

    /**
     * Writes a JSON autosave to the project and to the session subfolder.
     *
     * @param snapshot   the session data to export
     * @param projectDir the project directory containing the report folder
     * @return the path of the primary JSON file in the project
     * @throws IOException if a directory or file cannot be created or replaced
     */
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

    /**
     * Writes JSON and PDF reports to the project, to the session subfolder and
     * to a timestamped backup directory.
     *
     * @param snapshot   the session data to export
     * @param projectDir the project directory containing the report folder
     * @return the primary report directory ({@code Telemetry-Report})
     * @throws IOException if a directory or file cannot be created or copied
     */
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

    /**
     * Resolves a stable folder name for a session, disambiguating collisions
     * when two sessions start within the same second.
     *
     * @param reportDir the project's report directory
     * @param snapshot  the snapshot whose session id and start time are used
     * @return the resolved session folder path
     */
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

    /**
     * Checks whether an existing session folder already holds a report for the
     * given session id, using a simple text search for the JSON session id.
     *
     * @param sessionDir the candidate folder
     * @param sessionId  the session id to look for
     * @return {@code true} if the folder's JSON report belongs to the session
     */
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

    /**
     * Writes a report atomically by serializing to a temporary file and moving
     * it into place, falling back to a non-atomic move when the file system
     * does not support atomic moves.
     *
     * @param writer   the serializer to use
     * @param snapshot the data to serialize
     * @param path     the final destination path
     * @throws IOException if writing or moving the temporary file fails
     */
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
