package edu.uwi.comp3607.telemetry.core;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TelemetrySession {

    private static final class ProjectInfo {
        final Set<String> openPackages = new LinkedHashSet<>();
        final Set<String> knownClasses = new LinkedHashSet<>();
        final Map<String,Integer> methods = new HashMap<>();
        boolean everOpened;
    }

    private static final class EditorInfo {
        int length;
        Integer words;
        EditorInfo(int length, Integer words) { this.length = length; this.words = words; }
    }

    private static final class Compilation {
        boolean sawFirstError, sawFirstWarning;
    }


    private final String sessionId;
    private final Instant startedAt;
    private final IdleTracker idle;
    private final AnalyticsObserver analytics = new AnalyticsObserver();
    private final List<EventObserver> observers = new ArrayList<>();
    private final List<TelemetryEvent> events = new ArrayList<>();
    private final Map<String,ProjectInfo> projects = new LinkedHashMap<>();
    private final Map<String,EditorInfo> editors = new HashMap<>();
    private final Map<String,Compilation> compilations = new HashMap<>();
    private final Map<String,Integer> editWindows = new LinkedHashMap<>();
    private long positiveWordDelta;
    private String studentId;
    private Instant endedAt;

    public TelemetrySession(Instant startedAt) {
        this(UUID.randomUUID().toString(), startedAt); }

    public TelemetrySession(String sessionId, Instant startedAt) {
        this.sessionId = sessionId;
        this.startedAt = startedAt;
        this.idle = new IdleTracker(startedAt);
        observers.add(analytics);
    }

    public String sessionId() {
        return sessionId; }

    public void addObserver(EventObserver observer) {
        observers.add(observer); }

    public void setStudentId(String value) {
        studentId = value == null || value.isBlank() ? null : value.trim();
    }

    public String studentId() {
        return studentId; }

    public List<Path> projectDirectories() {
        return projects.keySet().stream().map(Path::of).toList();
    }

    public void openPackage(String projectDir, String packageName, List<String> baseline, Instant now) {
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        if (p.openPackages.add(packageName)) {
            if (!p.everOpened || p.openPackages.size() == 1) {
                record(EventFactory.event(now, "PROJECT_OPENED", projectDir, projectDir, "basis", "packageOpened"));
                p.everOpened = true;
            }
            record(EventFactory.event(now, "PACKAGE_OPENED", projectDir, packageName));
        }
        if (baseline != null) p.knownClasses.addAll(baseline);
    }


    public boolean closePackage(String projectDir, String packageName, Instant now) {
        ProjectInfo p = projects.get(projectDir);
        if (p == null || !p.openPackages.remove(packageName)) return false;
        record(EventFactory.event(now, "PACKAGE_CLOSING", projectDir, packageName));
        if (p.openPackages.isEmpty()) {
            record(EventFactory.event(now, "PROJECT_CLOSED", projectDir, projectDir, "basis", "last package closing"));
            return true;
        }
        return false;
    }

    public void classChanged(String projectDir, String className, Integer declaredMethods, Instant now) {
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        if (p.knownClasses.add(className)) {
            record(EventFactory.event(now, "CLASS_CREATED_INFERRED", projectDir, className));
        } else {
            record(EventFactory.event(now, "CLASS_MODIFIED", projectDir, className));
        }
        if (declaredMethods != null && declaredMethods >= 0) {
            Integer previous = p.methods.put(className, declaredMethods);
            if (previous == null || previous != declaredMethods) {
                record(EventFactory.event(now, "METHODS_SAMPLED", projectDir, className,
                        "declaredMethodCount", declaredMethods, "delta", previous == null ? null : declaredMethods - previous,
                        "compiledOnly", true));
            }
        }
    }

    public void classRenamed(String projectDir, String oldName, String newName, Instant now) {
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        p.knownClasses.remove(oldName);
        p.knownClasses.add(newName);
        Integer methods = p.methods.remove(oldName);
        if (methods != null) p.methods.put(newName, methods);
        record(EventFactory.event(now, "CLASS_RENAMED", projectDir, newName, "oldName", oldName));
    }

    public void classRemoved(String projectDir, String className, Instant now) {
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        p.knownClasses.remove(className);
        p.methods.remove(className);
        editors.remove(projectDir + "::" + className);
        record(EventFactory.event(now, "CLASS_REMOVED", projectDir, className));
    }

    public void compilationStarted(String projectDir, String files, Instant now) {
        compilations.put(projectDir, new Compilation());
        record(EventFactory.event(now, "COMPILE_STARTED", projectDir, files, "userTriggered", true));
    }

    public void compilationProblem(String projectDir, String file, String message, int line,
                                   boolean warning, Instant now) {
        Compilation c = compilations.computeIfAbsent(projectDir, unused -> new Compilation());
        if (warning ? c.sawFirstWarning : c.sawFirstError) return;
        if (warning) c.sawFirstWarning = true;
        else c.sawFirstError = true;
        String text = message == null ? "" : message.trim();
        String firstFile = file == null ? "" : file.split(",", 2)[0].trim();
        String className = firstFile.endsWith(".java") ? firstFile.substring(0, firstFile.length() - 5) : firstFile;
        record(EventFactory.event(now, warning ? "COMPILE_WARNING" : "COMPILE_ERROR", projectDir, file,
                "class", className.isEmpty() ? null : className,
                "message", text, "line", line > 0 ? line : null,
                "errorCode", EventFactory.errorCategory(text), "derivedErrorCode", true));
    }

    public void compilationFinished(String projectDir, String files, boolean success, Instant now) {
        compilations.remove(projectDir);
        record(EventFactory.event(now, success ? "COMPILE_SUCCEEDED" : "COMPILE_FAILED", projectDir, files));
    }

    public void execution(String projectDir, String className, String method, String outcome, Instant now) {
        String status = Set.of("NORMAL", "EXCEPTION", "TERMINATED", "UNKNOWN").contains(outcome)
                ? outcome : "UNKNOWN";
        record(EventFactory.event(now, "EXECUTION_FINISHED", projectDir, className,
                "method", method, "outcome", status, "consoleOutput", null, "stackTrace", null));
    }

    public void editorSample(String projectDir, String className, int length, Integer wordCount, Instant now) {
        if (length < 0) return;
        String key = projectDir + "::" + className;
        EditorInfo previous = editors.get(key);
        if (previous == null) {
            editors.put(key, new EditorInfo(length, wordCount));
            return;
        }
        boolean changed = previous.length != length;
        boolean nonIdle = idle.isNonIdle(now) || changed;
        if (changed) {
            String window = String.valueOf(Math.max(0L, Duration.between(startedAt, now).toMinutes()) / 5L);
            editWindows.merge(window, 1, Integer::sum);
            record(EventFactory.event(now, "EDITOR_LENGTH_CHANGED", projectDir, className,
                    "previousLength", previous.length, "currentLength", length,
                    "fiveMinuteWindowIndex", Integer.parseInt(window)));
        }
        previous.length = length;
        if (wordCount != null) {
            long added = 0;
            if (previous.words != null && nonIdle && wordCount > previous.words)
                added = wordCount - previous.words;
            positiveWordDelta += added;
            previous.words = wordCount;
            record(EventFactory.event(now, "EDITOR_WORD_SAMPLE", projectDir, className,
                    "sampledWordCount", wordCount, "estimatedPositiveNetNewWords", added,
                    "acceptedAsNonIdle", nonIdle));
        }
    }

    public void record(TelemetryEvent event) {
        if (endedAt != null) return;
        events.add(event);
        switch (event.type()) {
            case "EDITOR_WORD_SAMPLE", "METHODS_SAMPLED" -> { }
            default -> idle.activity(event.timestamp());
        }
        for (EventObserver observer : observers) observer.onEvent(event);
    }

    public void finish(Instant now) { if (endedAt == null) endedAt = now; }

    public SessionSnapshot snapshot(Instant at) {
        Instant effective = endedAt == null ? at : endedAt;
        long duration = idle.durationMillis(effective), active = idle.activeMillis(effective), passive = idle.idleMillis(effective);
        double cadence = active == 0 ? 0.0 : positiveWordDelta * 60_000.0 / active;
        return SessionSnapshot.builder(sessionId, startedAt)
                .studentId(studentId)
                .times(effective, endedAt, duration, active, passive)
                .compilations(analytics)
                .editor(positiveWordDelta, cadence, editWindows)
                .events(events)
                .build();
    }
}
