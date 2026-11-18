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

/**
 * Coordinates telemetry collection during a BlueJ session.
 *
 * <p>Stores normalized telemetry events, manages project and class
 * observations, tracks compilation history, and produces immutable
 * report snapshots. A built-in {@link AnalyticsObserver} maintains aggregate
 * compilation and execution counts; additional observers can be registered
 * with {@link #addObserver(EventObserver)}.</p>
 *
 * <p><strong>Thread-safety:</strong> session mutations are intended to run on
 * the telemetry worker thread owned by
 * {@link edu.uwi.comp3607.telemetry.bluej.TelemetryRuntime}. Snapshot creation
 * reads the same state and should be performed on that thread as well; this
 * class performs no internal synchronization.</p>
 */
public final class TelemetrySession {

    /**
     * A single compiler diagnostic associated with a compilation attempt.
     *
     * @param file     the source file, or comma-separated file list, reported by BlueJ
     * @param message  the trimmed diagnostic message
     * @param line     the one-based line number, or {@code null} when unavailable
     * @param category the derived error category
     * @param warning  {@code true} for a warning, {@code false} for an error
     */
    public record CompilationDiagnostic( String file, String message, Integer line, String category, boolean warning) {}

    /**
     * Mutable record of one compilation attempt for a project.
     *
     * <p>The most recent attempt is kept in the per-project lookup map so that
     * later diagnostics can be attached to it, while every attempt is retained
     * in the session-wide history. Attempts are created and mutated only on the
     * telemetry worker thread; diagnostic lists are exposed as unmodifiable
     * views.</p>
     */
    public static final class Compilation {
        /** Monotonically increasing identifier assigned when the attempt begins. */
        final int attemptId;

        /** Normalized directory of the project being compiled. */
        final String projectPath;

        /** Time at which the attempt was first observed. */
        final Instant startedAt;

        /** Comma-separated names of the source files involved. */
        final String sourceFiles;

        /** Error diagnostics collected for this attempt, in arrival order. */
        final List<CompilationDiagnostic> errors = new ArrayList<>();

        /** Warning diagnostics collected for this attempt, in arrival order. */
        final List<CompilationDiagnostic> warnings = new ArrayList<>();

        /** Current state: {@code IN_PROGRESS}, {@code SUPERSEDED}, {@code SUCCEEDED} or {@code FAILED}. */
        String status = "IN_PROGRESS";

        /**
         * Creates an in-progress compilation attempt.
         *
         * @param attemptId   the unique identifier of the attempt
         * @param projectPath the normalized project directory
         * @param startedAt   the time the attempt was observed
         * @param sourceFiles the source file names involved
         */
        Compilation(int attemptId, String projectPath, Instant startedAt, String sourceFiles) {
            this.attemptId = attemptId;
            this.projectPath = projectPath;
            this.startedAt = startedAt;
            this.sourceFiles = sourceFiles;
        }

        /** @return the unique attempt identifier */
        public int attemptId() { return attemptId; }

        /** @return the normalized project directory */
        public String projectPath() { return projectPath; }

        /** @return the time the attempt started */
        public Instant startedAt() { return startedAt; }

        /** @return the comma-separated source file names */
        public String sourceFiles() { return sourceFiles; }

        /** @return an unmodifiable view of the error diagnostics */
        public List<CompilationDiagnostic> errors() { return Collections.unmodifiableList(errors); }

        /** @return an unmodifiable view of the warning diagnostics */
        public List<CompilationDiagnostic> warnings() { return Collections.unmodifiableList(warnings); }

        /** @return the current status string */
        public String status() { return status; }

        /** @return the first error diagnostic, or {@code null} when there are none */
        public CompilationDiagnostic firstError() { return errors.isEmpty() ? null : errors.get(0); }
    }

    /** Internal per-project tracking state for observed packages and classes. */
    private static final class ProjectInfo{
        /** Package names currently open in this project, in first-seen order. */
        final Set<String> openPackages = new LinkedHashSet<>();

        /** All class names known to the session for this project. */
        final Set<String> knownClasses = new LinkedHashSet<>();

        /** Last observed compiled method count per class. */
        final Map<String, Integer> methods = new HashMap<>();

        /** Last observed state signature per class, used to infer modifications. */
        final Map<String, String> lastClassState = new HashMap<>();

        /** Whether a package of this project has ever been opened in this session. */
        boolean everOpened;
    }

    /** Internal snapshot of the most recent editor sample for one class. */
    private static final class EditorInfo {
        /** Last observed character count. */
        int length;

        /** Last observed word count, when word sampling was enabled. */
        Integer words;

        /** Content hash used to detect same-length edits between samples. */
        Integer fingerprint;

        /**
         * Creates an editor sample record.
         *
         * @param length      the observed character count
         * @param words       the observed word count, or {@code null}
         * @param fingerprint the content hash, or {@code null}
         */
        EditorInfo(int length, Integer words, Integer fingerprint) {
            this.length = length;
            this.words = words;
            this.fingerprint = fingerprint;
        }
    }

    /** Unique identifier assigned to the current telemetry session. */
    private final String sessionId;

    /** Time at which the session started; also the idle tracker's baseline. */
    private final Instant startedAt;

    /** Tracks estimated idle and active time. */
    private final IdleTracker idle;

    /** Observer that maintains aggregate compilation and execution statistics. */
    private final AnalyticsObserver analytics = new AnalyticsObserver();

    /** Observers notified synchronously for every recorded event. */
    private final List<EventObserver> observers = new ArrayList<>();

    /** Stores telemetry events in their recorded order. */
    private final List<TelemetryEvent> events = new ArrayList<>();

    /** Per-project observation state, keyed by normalized project directory. */
    private final Map<String,ProjectInfo> projects = new LinkedHashMap<>();

    /** Most recent editor sample per project/class key. */
    private final Map<String,EditorInfo> editors = new HashMap<>();

    /** Currently active compilation per project directory. */
    private final Map<String,Compilation> compilations = new HashMap<>();

    /** All compilation attempts in the order they were started. */
    private final List<Compilation> compilationHistory = new ArrayList<>();

    /** Edit counts keyed by five-minute window index since the session started. */
    private final Map<String,Integer> editWindows = new LinkedHashMap<>();

    /** Source of monotonically increasing compilation attempt identifiers. */
    private int compilationAttemptCounter;

    /** Accumulated positive net new words accepted as non-idle edits. */
    private long positiveWordDelta;

    /** Student identifier supplied at export time, or {@code null}. */
    private String studentId;

    /** Time at which the session was finished, or {@code null} while still running. */
    private Instant endedAt;

    /**
     * Creates a session with a generated random identifier.
     *
     * @param startedAt the session start instant
     */
    public TelemetrySession(Instant startedAt) {
        this(UUID.randomUUID().toString(), startedAt); }

    /**
     * Creates a session with an explicit identifier.
     *
     * @param sessionId the unique session identifier
     * @param startedAt the session start instant
     */
    public TelemetrySession(String sessionId, Instant startedAt) {
        this.sessionId = sessionId;
        this.startedAt = startedAt;
        this.idle = new IdleTracker(startedAt);
        observers.add(analytics);
    }

    /** @return the unique session identifier */
    public String sessionId() {
        return sessionId; }

    /**
     * Registers an additional observer for recorded events.
     *
     * @param observer the observer to notify; must not be {@code null}
     */
    public void addObserver(EventObserver observer) {
        observers.add(observer); }

    /**
     * Sets the student identifier, normalizing blank values to {@code null}.
     *
     * @param value the raw identifier; may be {@code null} or blank
     */
    public void setStudentId(String value) {
        studentId = value == null || value.isBlank() ? null : value.trim();
    }

    /** @return the normalized student identifier, or {@code null} if unset */
    public String studentId() {
        return studentId; }

    /** @return the normalized directories of all observed projects */
    public List<Path> projectDirectories() {
        return projects.keySet().stream().map(Path::of).toList();
    }

    /** @return an unmodifiable list of all compilation attempts in start order */
    public List<Compilation> compilationHistory() {
        return Collections.unmodifiableList(compilationHistory);
    }

    /**
     * Records that a package was opened, establishing the project's class baseline.
     *
     * <p>A {@code PROJECT_OPENED} event is emitted for the first package of a
     * project, and a {@code PACKAGE_OPENED} event for every new package. Repeated
     * openings of an already-open package are ignored.</p>
     *
     * @param projectDir  the normalized project directory; {@code null} is ignored
     * @param packageName the package name, or {@code null} for the default package
     * @param baseline    class names known to be present, or {@code null}
     * @param now         the time of the observation
     */
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

    /**
     * Records that a package is closing and, when it was the last one, that the
     * project itself closed and per-project caches are discarded.
     *
     * @param projectDir  the normalized project directory
     * @param packageName the package name, or {@code null} for the default package
     * @param now         the time of the observation
     * @return {@code true} if this was the project's last open package
     */
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

    /**
     * Records a class creation or modification based on a state signature, and
     * samples the declared method count of compiled classes.
     *
     * @param projectDir      the normalized project directory; {@code null} is ignored
     * @param className       the simple class name; blank values are ignored
     * @param declaredMethods the compiled method count, or {@code null} when unavailable
     * @param now             the time of the observation
     */
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

    /**
     * Records a class rename and migrates all per-class tracking state to the new name.
     *
     * @param projectDir the normalized project directory
     * @param oldName    the previous class name
     * @param newName    the new class name
     * @param now        the time of the observation
     */
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

    /**
     * Records a class removal and drops all per-class tracking state.
     *
     * @param projectDir the normalized project directory
     * @param className  the class name
     * @param now        the time of the observation
     */
    public void classRemoved(String projectDir, String className, Instant now) {
        if (projectDir == null || className == null || className.isBlank()) return;
        ProjectInfo p = projects.get(projectDir);
        if (p == null || !p.knownClasses.remove(className)) return;
        p.methods.remove(className);
        p.lastClassState.remove(className);
        editors.remove(projectDir + "::" + className);
        record(EventFactory.event(now, "CLASS_REMOVED", projectDir, className));
    }

    /**
     * Starts a new compilation attempt, marking any still-open attempt for the
     * same project as superseded so that late diagnostics cannot be attributed
     * to the wrong attempt.
     *
     * @param projectDir the normalized project directory
     * @param files      the source file names involved
     * @param now        the time the attempt started
     * @param inferred   {@code true} when the start was inferred from a diagnostic
     * @return the newly created attempt
     */
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

    /**
     * Records a user-triggered compilation start.
     *
     * @param projectDir the normalized project directory; {@code null} is ignored
     * @param files      the source file names involved
     * @param now        the time the compilation started
     */
    public void compilationStarted(String projectDir, String files, Instant now) {
        if (projectDir == null) return;
        beginCompilation(projectDir, files, now, false);
    }

    /**
     * Records a compiler diagnostic, inferring a compilation attempt when none
     * is currently open for the project. Error codes are coarse derived
     * categories, not compiler-specific diagnostic codes.
     *
     * @param projectDir the normalized project directory; {@code null} is ignored
     * @param file       the source file or comma-separated file list
     * @param message    the raw diagnostic message
     * @param line       the reported line number; values &lt;= 0 are omitted
     * @param warning    {@code true} for a warning, {@code false} for an error
     * @param now        the time of the observation
     */
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

    /**
     * Records the outcome of the current compilation attempt, inferring an
     * attempt first if no start event was observed.
     *
     * @param projectDir the normalized project directory; {@code null} is ignored
     * @param files      the source file names involved
     * @param success    {@code true} if the compilation succeeded
     * @param now        the time the compilation finished
     */
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

    /**
     * Records the completion of a method invocation, normalizing unknown outcome
     * names to {@code UNKNOWN}.
     *
     * @param projectDir the normalized project directory
     * @param className  the class whose method was invoked
     * @param method     the invoked method name
     * @param outcome    one of {@code NORMAL}, {@code EXCEPTION}, {@code TERMINATED} or {@code UNKNOWN}
     * @param now        the time of the observation
     */
    public void execution(String projectDir, String className, String method, String outcome, Instant now) {
        String status = Set.of("NORMAL", "EXCEPTION", "TERMINATED", "UNKNOWN").contains(outcome)
                ? outcome : "UNKNOWN";
        record(EventFactory.event(now, "EXECUTION_FINISHED", projectDir, className,
                "method", method, "outcome", status, "consoleOutput", null, "stackTrace", null));
    }

    /**
     * Records an editor sample without a content fingerprint.
     *
     * @param projectDir the normalized project directory
     * @param className  the class whose editor was sampled
     * @param length     the character count
     * @param wordCount  the word count, or {@code null} when not sampled
     * @param now        the time of the sample
     */
    public void editorSample(String projectDir, String className, int length, Integer wordCount, Instant now) {
        editorSample(projectDir, className, length, wordCount, null, now);
    }

    /**
     * Records an editor sample, detecting changes from the character count or a
     * content fingerprint and counting positive word deltas only when the
     * student is not idle.
     *
     * <p>Detectable edits are bucketed into five-minute windows relative to the
     * session start. Exact keystrokes are not captured: same-length changes are
     * visible only when a fingerprint is supplied.</p>
     *
     * @param projectDir  the normalized project directory; {@code null} is ignored
     * @param className   the class whose editor was sampled; blank values are ignored
     * @param length      the observed character count; negative values are ignored
     * @param wordCount   the word count, or {@code null} when not sampled
     * @param fingerprint the content hash used to detect same-length edits, or {@code null}
     * @param now         the time of the sample
     */
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

    /**
     * Stores an event, updates the idle tracker and notifies observers.
     *
     * <p>Events recorded after {@link #finish(Instant)} are silently discarded.
     * High-frequency sampling events ({@code EDITOR_WORD_SAMPLE} and
     * {@code METHODS_SAMPLED}) do not refresh the idle tracker because they are
     * produced by the polling timer rather than by student interaction.</p>
     *
     * @param event the event to record
     */
    public void record(TelemetryEvent event) {
        if (endedAt != null) return;
        events.add(event);
        switch (event.type()) {
            case "EDITOR_WORD_SAMPLE", "METHODS_SAMPLED" -> { }
            default -> idle.activity(event.timestamp());
        }
        for (EventObserver observer : observers) observer.onEvent(event);
    }

    /**
     * Marks the session as finished, preventing any further events from being recorded.
     *
     * @param now the time at which collection stopped
     */
    public void finish(Instant now) { if (endedAt == null) endedAt = now; }

    /**
     * Creates an immutable snapshot of the telemetry session.
     *
     * <p>If the session has finished, the recorded end time is used in place of
     * the requested instant so that all reports for a session are stable.</p>
     *
     * @param at the requested snapshot time
     * @return an immutable report snapshot
     */
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
