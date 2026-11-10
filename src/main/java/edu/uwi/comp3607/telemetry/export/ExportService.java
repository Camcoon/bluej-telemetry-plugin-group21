package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class ExportService {
    private final ReportWriter json = new JsonReportWriter();
    private final ReportWriter pdf = new PdfReportWriter();
    private final Path backupRoot;

    public ExportService() {
        this(Path.of(System.getProperty("user.home"), ".bluej-telemetry", "backups"));
    }
    public ExportService(Path backupRoot) { this.backupRoot = backupRoot; }

    public Path exportJsonAutosave(SessionSnapshot snapshot, Path projectDir) throws IOException {
        Path dir = projectDir.resolve("Telemetry-Report");
        Files.createDirectories(dir);
        Path result = dir.resolve("telemetry-session.json");
        atomicReport(json, snapshot, result);
        return result;
    }

    public Path exportAll(SessionSnapshot snapshot, Path projectDir) throws IOException {
        Path dir = projectDir.resolve("Telemetry-Report");
        Files.createDirectories(dir);
        Path jsonFile = dir.resolve("telemetry-session.json");
        Path pdfFile = dir.resolve("telemetry-report.pdf");
        atomicReport(json, snapshot, jsonFile);
        atomicReport(pdf, snapshot, pdfFile);
        String projectName = projectDir.getFileName() == null ? "project" : projectDir.getFileName().toString();
        String safe = projectName.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path backupDir = backupRoot.resolve(snapshot.sessionId).resolve(safe)
                .resolve(Instant.now().toString().replace(':', '-'));
        Files.createDirectories(backupDir);
        Files.copy(jsonFile, backupDir.resolve("telemetry-session.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pdfFile, backupDir.resolve("telemetry-report.pdf"), StandardCopyOption.REPLACE_EXISTING);
        return dir;
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
