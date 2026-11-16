package edu.uwi.comp3607.telemetry.export;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import java.io.IOException;
import java.nio.file.Path;

public interface ReportWriter {
    void write(SessionSnapshot snapshot, Path destination) throws IOException;
}
