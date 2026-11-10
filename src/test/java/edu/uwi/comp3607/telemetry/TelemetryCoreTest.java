package edu.uwi.comp3607.telemetry;

import edu.uwi.comp3607.telemetry.core.*;
import edu.uwi.comp3607.telemetry.export.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryCoreTest {
    private static final Instant START = Instant.parse("2026-11-09T12:00:00Z");
    private static Instant after(long sec) { return START.plusSeconds(sec); }

    @Test void retroactiveIdleIsEntireGapAndBalances() {
        IdleTracker tracker = new IdleTracker(START);
        assertEquals(0, tracker.idleMillis(after(149)));
        assertEquals(150_000L, tracker.idleMillis(after(150)));
        assertEquals(0L, tracker.activeMillis(after(150)));
        tracker.activity(after(160));
        assertEquals(160_000L, tracker.idleMillis(after(170)));
        tracker.activity(after(175));
        assertEquals(15_000L, tracker.activeMillis(after(175)));
        assertTrue(tracker.isNonIdle(after(176)));
        assertFalse(tracker.isNonIdle(after(330)));
        assertEquals(175_000L, tracker.durationMillis(after(175)));
        tracker.activity(after(174)); // out-of-order event ignored
        assertEquals(160_000L, tracker.idleMillis(after(175)));
    }

    @Test void baselineClassAndMethodTimelineAreNotFabricated() {
        TelemetrySession s = new TelemetrySession("test-session", START);
        String path = "/tmp/MyBlueJProject";
        s.openPackage(path, "", List.of("OldClass"), START);
        s.openPackage(path, "pkg", List.of("pkg.Existing"), after(1));
        s.classChanged(path, "OldClass", null, after(2));
        s.classChanged(path, "NewClass", 2, after(3));
        s.classChanged(path, "NewClass", 3, after(4));
        s.classRenamed(path, "NewClass", "Renamed", after(5));
        s.classRemoved(path, "Renamed", after(6));
        assertFalse(s.closePackage(path, "", after(7)));
        assertTrue(s.closePackage(path, "pkg", after(8)));
        assertFalse(s.closePackage(path, "pkg", after(9))); // duplicate event
        SessionSnapshot snapshot = s.snapshot(after(10));
        long projectsOpened = snapshot.events.stream().filter(e -> e.type().equals("PROJECT_OPENED")).count();
        long projectsClosed = snapshot.events.stream().filter(e -> e.type().equals("PROJECT_CLOSED")).count();
        long created = snapshot.events.stream().filter(e -> e.type().equals("CLASS_CREATED_INFERRED")).count();
        long samples = snapshot.events.stream().filter(e -> e.type().equals("METHODS_SAMPLED")).count();
        assertEquals(1, projectsOpened);
        assertEquals(1, projectsClosed);
        assertEquals(1, created);
        assertEquals(2, samples);
        assertEquals(1, s.projectDirectories().size());
        assertEquals(snapshot.durationMillis, snapshot.activeMillis + snapshot.idleMillis);
    }

    @Test void observerFirstCompilerDiagnosticsAndExecutionOutcomes() {
        TelemetrySession s = new TelemetrySession(START);
        AtomicInteger observed = new AtomicInteger();
        s.addObserver(event -> observed.incrementAndGet());
        String dir = "/tmp/Example";
        s.compilationStarted(dir, "Hello.java", after(1));
        s.compilationProblem(dir, "Hello.java", "cannot find symbol", 13, false, after(2));
        s.compilationProblem(dir, "Hello.java", "missing return", 18, false, after(3));
        s.compilationProblem(dir, "Hello.java", "deprecated API", 5, true, after(4));
        s.compilationProblem(dir, "Hello.java", "other warning", 8, true, after(5));
        s.compilationFinished(dir, "Hello.java", false, after(6));
        s.compilationStarted(dir, "Hello.java", after(7));
        s.compilationFinished(dir, "Hello.java", true, after(8));
        s.execution(dir, "Hello", "main", "NORMAL", after(9));
        s.execution(dir, "Hello", null, "EXCEPTION", after(10));
        s.execution(dir, "Hello", "run", "TERMINATED", after(11));
        s.execution(dir, "Hello", "run", "UNRECOGNISED", after(12));
        SessionSnapshot snap = s.snapshot(after(13));
        assertEquals(2, snap.compileAttempts);
        assertEquals(1, snap.compileSucceeded);
        assertEquals(1, snap.compileFailed);
        assertEquals(1, snap.compileErrors);
        assertEquals(1, snap.compileWarnings);
        assertEquals(50, snap.compileSuccessRatePercent);
        assertEquals(1, snap.errorBreakdown.get("MISSING_SYMBOL").intValue());
        assertEquals(1, snap.executionOutcomes.get("NORMAL").intValue());
        assertEquals(1, snap.executionOutcomes.get("EXCEPTION").intValue());
        assertEquals(1, snap.executionOutcomes.get("UNKNOWN").intValue());
        assertEquals(snap.events.size(), observed.get());
    }

    @Test void editorLengthChangesAreOnlyApproximateAndWordSamplesStayNonIdle() {
        TelemetrySession s = new TelemetrySession(START);
        String project = "/tmp/Hello";
        s.editorSample(project, "Hello", 100, 20, after(0)); // baseline
        s.editorSample(project, "Hello", 100, null, after(30)); // same-length edits invisible
        s.editorSample(project, "Hello", 110, 25, after(60));
        s.editorSample(project, "Hello", 115, 30, after(90));
        s.editorSample(project, "Hello", 115, 45, after(400)); // idle: discard positive delta
        SessionSnapshot snap = s.snapshot(after(400));
        assertEquals(10, snap.estimatedNewWords);
        assertEquals(2, snap.editsPerFiveMinuteWindow.get("0").intValue());
        assertTrue(snap.estimatedWordsPerActiveMinute > 0);
        assertTrue(snap.idleMillis >= 300_000L);
        assertEquals(2L, snap.events.stream().filter(e -> e.type().equals("EDITOR_LENGTH_CHANGED")).count());
    }

    @Test void factoryEscapingAndDerivedCategories() {
        assertEquals("MISSING_SYMBOL", EventFactory.errorCategory("cannot find symbol: x"));
        assertEquals("TYPE_MISMATCH", EventFactory.errorCategory("incompatible types"));
        assertEquals("MISSING_RETURN", EventFactory.errorCategory("missing return statement"));
        assertEquals("SYNTAX", EventFactory.errorCategory("';' expected"));
        assertEquals("MISSING_IMPORT", EventFactory.errorCategory("package a.b does not exist"));
        assertEquals("OTHER", EventFactory.errorCategory(null));
        assertThrows(IllegalArgumentException.class, () -> EventFactory.event(START, "T", "p", "x", "odd"));
        assertThrows(IllegalArgumentException.class, () -> EventFactory.event(START, "T", "p", "x", 1, 2));
        TelemetryEvent item = EventFactory.event(START, "T", "p", "x", "message", "quoted \"value\"");
        assertThrows(UnsupportedOperationException.class, () -> item.details().put("new", 1));
        TelemetrySession session = new TelemetrySession(START);
        session.record(item);
        String json = new JsonReportWriter().toJson(session.snapshot(after(1)));
        assertTrue(json.contains("quoted \\\"value\\\""));
        assertTrue(json.contains("\"studentId\": null"));
        assertTrue(json.contains("\"totalCompilationAttempts\": 0"));
    }

    @Test void exportersPlaceOutputsUnderProjectAndBackupsOutside(@TempDir Path folder) throws Exception {
        Path project = folder.resolve("MyBlueJProject");
        Files.createDirectories(project);
        Path backup = folder.resolve("external-backups");
        TelemetrySession s = new TelemetrySession(START);
        s.openPackage(project.toString(), "", List.of(), START);
        s.setStudentId("UWI-123");
        s.compilationStarted(project.toString(), "MyClass.java", after(1));
        s.compilationProblem(project.toString(), "MyClass.java", "';' expected", 7, false, after(2));
        s.compilationFinished(project.toString(), "MyClass.java", false, after(3));
        s.classChanged(project.toString(), "MyClass", 1, after(4));
        s.editorSample(project.toString(), "MyClass", 50, 10, after(5));
        s.editorSample(project.toString(), "MyClass", 65, 13, after(25));
        s.execution(project.toString(), "MyClass", "run", "EXCEPTION", after(26));
        s.finish(after(30));
        SessionSnapshot snap = s.snapshot(after(999));
        assertEquals(after(30), snap.endedAtUtc);
        ExportService exporter = new ExportService(backup);
        Path auto = exporter.exportJsonAutosave(snap, project);
        assertEquals(project.resolve("Telemetry-Report/telemetry-session.json"), auto);
        Path dir = exporter.exportAll(snap, project);
        Path pdf = dir.resolve("telemetry-report.pdf");
        byte[] bytes = Files.readAllBytes(pdf);
        assertTrue(bytes.length > 2000);
        assertEquals("%PDF-1.4", new String(bytes, 0, 8));
        String content = Files.readString(auto);
        assertTrue(content.contains("\"studentId\": \"UWI-123\""));
        assertTrue(content.contains("\"EXCEPTION\": 1"));
        try (var stream = Files.walk(backup)) {
            assertEquals(2, stream.filter(Files::isRegularFile).count());
        }
    }
}
