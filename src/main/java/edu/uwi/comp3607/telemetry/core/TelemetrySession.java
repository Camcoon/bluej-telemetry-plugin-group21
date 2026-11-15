package edu.uwi.comp3607.telemetry.core;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TelemetrySession {

    public record CompilationDiagnostic( String file, String message, Integer line, String category, boolean warning) {}
    
    public static final class Compilation {
        final int attemptId;
        final String projectPath;
        final Instant startedAt;
        final String sourceFiles;
        final List<CompilationDiagnostic> errors = new ArrayList<>();
        final List<CompilationDiagnostic> warnings = new ArrayList<>();
        String status = "IN_PROGRESS";

        Compilation(int attemptId, String projectPath, Instant startedAt, String sourceFiles) {
            this.attemptId = attemptId;
            this.projectPath = projectPath;
            this.startedAt = startedAt;
            this.sourceFiles = sourceFiles;
        }

        public int attemptId() { return attemptId; }
        public String projectPath() { return projectPath; }
        public Instant startedAt() { return startedAt; }
        public String sourceFiles() { return sourceFiles; }
        public List<CompilationDiagnostic> errors() { return Collections.unmodifiableList(errors); }
        public List<CompilationDiagnostic> warnings() { return Collections.unmodifiableList(warnings); }
        public String status() { return status; }
        public CompilationDiagnostic firstError() { return errors.isEmpty() ? null : errors.get(0); }
    }

    private static final class ProjectInfo{
        final Set<String> openPackages = new LinkedHashSet<>();
        final Set<String> knownClasses = new LinkedHashSet<>();
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, String> lastClassState = new HashMap<>();
        boolean everOpened;
    }
    
    private static final class EditorInfo {
        int length;
        Integer words;
        Integer fingerprint;
        EditorInfo(int length, Integer words, Integer fingerprint) {
            this.length = length;
            this.words = words;
            this.fingerprint = fingerprint;
        }
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
    private final List<Compilation> compilationHistory = new ArrayList<>();
    private final Map<String,Integer> editWindows = new LinkedHashMap<>();
    private int compilationAttemptCounter;
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

    public List<Compilation> compilationHistory() {
        return Collections.unmodifiableList(compilationHistory);
    }

    public void openPackage(String projectDir, String packageName, List<String> baseline, Instant now) {
        if (projectDir == null) return;
        String pkgKey = packageName == null ? "" : packageName;
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        if (p.openPackages.add(pkgKey)) {
            if (!p.everOpened || p.openPackages.size() == 1) {
                record(EventFactory.event(now, "PROJECT_OPENED", projectDir, projectDir, "basis", "packageOpened"));
                p.everOpened = true;
            }
            record(EventFactory.event(now, "PACKAGE_OPENED", projectDir, pkgKey));
            if (baseline != null) {
                for (String cls : baseline) {
                    if (cls != null && !cls.isBlank()) p.knownClasses.add(cls);
                }
            }
        }
    }


    public boolean closePackage(String projectDir, String packageName, Instant now) {
        if (projectDir == null) return false;
        String pkgKey = packageName == null ? "" : packageName;
        ProjectInfo p = projects.get(projectDir);
        if (p == null || !p.openPackages.remove(pkgKey)) return false;
        record(EventFactory.event(now, "PACKAGE_CLOSING", projectDir, pkgKey));
        if (p.openPackages.isEmpty()) {
            p.knownClasses.clear();
            p.methods.clear();
            p.lastClassState.clear();
            record(EventFactory.event(now, "PROJECT_CLOSED", projectDir, projectDir, "basis", "last package closing"));
            return true;
        }
        return false;
    }

    public void classChanged(String projectDir, String className, Integer declaredMethods, Instant now) {
        if (projectDir == null || className == null || className.isBlank()) return;
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        String stateSignature = (declaredMethods != null && declaredMethods >= 0)
                ? "COMPILED:" + declaredMethods
                : "UNCOMPILED";
        boolean isNewClass = p.knownClasses.add(className);
        String previousState = p.lastClassState.put(className, stateSignature);
        if (isNewClass) {
            record(EventFactory.event(now, "CLASS_CREATED_INFERRED", projectDir, className));
        } else if (!stateSignature.equals(previousState)) {
            record(EventFactory.event(now, "CLASS_MODIFIED", projectDir, className));
        }
        if (declaredMethods != null && declaredMethods >= 0) {
            Integer previous = p.methods.put(className, declaredMethods);
            if (previous == null || !previous.equals(declaredMethods)) {
                record(EventFactory.event(now, "METHODS_SAMPLED", projectDir, className,
                        "declaredMethodCount", declaredMethods, "delta", previous == null ? null : declaredMethods - previous,
                        "compiledOnly", true));
            }
        }
    }

    public void classRenamed(String projectDir, String oldName, String newName, Instant now) {
        if (projectDir == null || oldName == null || newName == null
                || oldName.isBlank() || newName.isBlank() || oldName.equals(newName)) return;
        ProjectInfo p = projects.computeIfAbsent(projectDir, unused -> new ProjectInfo());
        boolean removedOld = p.knownClasses.remove(oldName);
        boolean addedNew = p.knownClasses.add(newName);
        if (!removedOld && !addedNew) return;
        Integer methods = p.methods.remove(oldName);
        if (methods != null) p.methods.put(newName, methods);
        String state = p.lastClassState.remove(oldName);
        if (state != null) p.lastClassState.put(newName, state);
        EditorInfo editor = editors.remove(projectDir + "::" + oldName);
        if (editor != null) editors.put(projectDir + "::" + newName, editor);
        record(EventFactory.event(now, "CLASS_RENAMED", projectDir, newName, "oldName", oldName));
    }

    public void classRemoved(String projectDir, String className, Instant now) {
        if (projectDir == null || className == null || className.isBlank()) return;
        ProjectInfo p = projects.get(projectDir);
        if (p == null || !p.knownClasses.remove(className)) return;
        p.methods.remove(className);
        p.lastClassState.remove(className);
        editors.remove(projectDir + "::" + className);
        record(EventFactory.event(now, "CLASS_REMOVED", projectDir, className));
    }

    private Compilation beginCompilation(String projectDir, String files, Instant now, boolean inferred) {
        Compilation previous = compilations.get(projectDir);
        if (previous != null && "IN_PROGRESS".equals(previous.status)) {
            previous.status = "SUPERSEDED";
        }
        Compilation c = new Compilation(++compilationAttemptCounter, projectDir, now, files);
        compilations.put(projectDir, c);
        compilationHistory.add(c);
        ProjectInfo p = projects.get(projectDir);
        if (p != null) p.lastClassState.clear();
        record(EventFactory.event(now, "COMPILE_STARTED", projectDir, files,
                "attemptId", c.attemptId, "userTriggered", !inferred,
                "inferredStart", inferred));
        return c;
    }

    public void compilationStarted(String projectDir, String files, Instant now) {
        if (projectDir == null) return;
        beginCompilation(projectDir, files, now, false);
    }

    public void compilationProblem(String projectDir, String file, String message, int line,
                                   boolean warning, Instant now) {
        if (projectDir == null) return;
        Compilation c = compilations.get(projectDir);
        if (c == null) {
            c = beginCompilation(projectDir, file, now, true);
        }
        String text = message == null ? "" : message.trim();
        Integer lineNumber = line > 0 ? line : null;
        String category = EventFactory.errorCategory(text);
        CompilationDiagnostic diagnostic = new CompilationDiagnostic(file, text, lineNumber, category, warning);
        boolean firstInAttempt;
        int diagnosticIndex;
        if (warning) {
            firstInAttempt = c.warnings.isEmpty();
            c.warnings.add(diagnostic);
            diagnosticIndex = c.warnings.size();
        } else {
            firstInAttempt = c.errors.isEmpty();
            c.errors.add(diagnostic);
            diagnosticIndex = c.errors.size();
        }
        String firstFile = file == null ? "" : file.split(",", 2)[0].trim();
        String className = firstFile.endsWith(".java") ? firstFile.substring(0, firstFile.length() - 5) : firstFile;
        record(EventFactory.event(now, warning ? "COMPILE_WARNING" : "COMPILE_ERROR", projectDir, file,
                "attemptId", c.attemptId,
                "diagnosticIndex", diagnosticIndex,
                "firstInAttempt", firstInAttempt,
                "class", className.isEmpty() ? null : className,
                "message", text, "line", lineNumber,
                "errorCode", category, "derivedErrorCode", true));
    }

    public void compilationFinished(String projectDir, String files, boolean success, Instant now) {
        if (projectDir == null) return;
        Compilation c = compilations.remove(projectDir);
        if (c == null) {
            c = beginCompilation(projectDir, files, now, true);
            compilations.remove(projectDir);
        }
        c.status = success ? "SUCCEEDED" : "FAILED";
        record(EventFactory.event(now, success ? "COMPILE_SUCCEEDED" : "COMPILE_FAILED", projectDir, files,
                "attemptId", c == null ? null : c.attemptId,
                "status", success ? "SUCCEEDED" : "FAILED",
                "errorCount", c == null ? 0 : c.errors.size(),
                "warningCount", c == null ? 0 : c.warnings.size()));
    }

    public void execution(String projectDir, String className, String method, String outcome, Instant now) {
        String status = Set.of("NORMAL", "EXCEPTION", "TERMINATED", "UNKNOWN").contains(outcome)
                ? outcome : "UNKNOWN";
        record(EventFactory.event(now, "EXECUTION_FINISHED", projectDir, className,
                "method", method, "outcome", status, "consoleOutput", null, "stackTrace", null));
    }

    public void editorSample(String projectDir, String className, int length, Integer wordCount, Instant now) {
        editorSample(projectDir, className, length, wordCount, null, now);
    }

    public void editorSample(String projectDir, String className, int length, Integer wordCount,
                             Integer fingerprint, Instant now) {
        if (projectDir == null || className == null || length < 0) return;
        String key = projectDir + "::" + className;
        EditorInfo previous = editors.get(key);
        if (previous == null) {
            editors.put(key, new EditorInfo(length, wordCount, fingerprint));
            return;
        }
        boolean lengthChanged = previous.length != length;
        boolean sameLengthChanged = !lengthChanged && fingerprint != null
                && previous.fingerprint != null && !fingerprint.equals(previous.fingerprint);
        boolean changed = lengthChanged || sameLengthChanged;
        boolean nonIdle = idle.isNonIdle(now) || changed;
        if (changed) {
            String window = String.valueOf(Math.max(0L, Duration.between(startedAt, now).toMinutes()) / 5L);
            editWindows.merge(window, 1, Integer::sum);
            record(EventFactory.event(now, lengthChanged ? "EDITOR_LENGTH_CHANGED" : "EDITOR_CONTENT_CHANGED",
                    projectDir, className, "previousLength", previous.length, "currentLength", length,
                    "sameLengthEdit", sameLengthChanged, "fiveMinuteWindowIndex", Integer.parseInt(window)));
        }
        previous.length = length;
        previous.fingerprint = fingerprint;
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
                .projectPaths(projects.keySet())
                .projectPath(projects.size() == 1 ? projects.keySet().iterator().next() : null)
                .events(events)
                .build();
    }
}
