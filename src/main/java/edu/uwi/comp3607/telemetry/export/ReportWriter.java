package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Strategy for serializing a {@link SessionSnapshot} to a file.
 *
 * <p>Implementations are stateless and may be reused for multiple reports.
 * The {@link ExportService} writes to a temporary file first and then moves it
 * into place, so implementations only need to write the complete content to
 * the supplied destination path.</p>
 */
public interface ReportWriter {
    /**
     * Writes the given snapshot to the destination path.
     *
     * @param snapshot    the immutable session data to serialize
     * @param destination the file to create or replace
     * @throws IOException if the file cannot be written
     */
    void write(SessionSnapshot snapshot, Path destination) throws IOException;
}
