package edu.uwi.comp3607.telemetry.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SessionSnapshot {
    public final String sessionId;
    public final String version;
    public final String studentId;
    public final String projectPath;
    public final List<String> projectPaths;
    public final Instant startedAtUtc;
    public final Instant snapshotAtUtc;
    public final Instant endedAtUtc;
    public final long durationMillis;
    public final long activeMillis;
    public final long idleMillis;
    public final int compileAttempts;
    public final int compileSucceeded;
    public final int compileFailed;
    public final int compileErrors;
    public final int compileWarnings;
    public final int firstCompileErrors;
    public final int firstCompileWarnings;
    public final double compileSuccessRatePercent;
    public final long estimatedNewWords;
    public final double estimatedWordsPerActiveMinute;
    public final Map<String, Integer> errorBreakdown;
    public final Map<String, Integer> executionOutcomes;
    public final Map<String, Integer> editsPerFiveMinuteWindow;
    public final List<TelemetryEvent> events;

    private SessionSnapshot(Builder b) {
        sessionId = b.sessionId;
        version = "1.0.3";
        studentId = b.studentId;
        projectPaths = Collections.unmodifiableList(new ArrayList<>(b.resolvedProjectPaths));
        projectPath = b.projectPath;
        startedAtUtc = b.startedAtUtc;
        snapshotAtUtc = b.snapshotAtUtc;
        endedAtUtc = b.endedAtUtc;
        durationMillis = b.durationMillis;
        activeMillis = b.activeMillis;
        idleMillis = b.idleMillis;
        compileAttempts = b.compileAttempts;
        compileSucceeded = b.compileSucceeded;
        compileFailed = b.compileFailed;
        compileErrors = b.compileErrors;
        compileWarnings = b.compileWarnings;
        firstCompileErrors = b.firstCompileErrors;
        firstCompileWarnings = b.firstCompileWarnings;
        compileSuccessRatePercent = b.compileSuccessRatePercent;
        estimatedNewWords = b.estimatedNewWords;
        estimatedWordsPerActiveMinute = b.estimatedWordsPerActiveMinute;
        errorBreakdown = Collections.unmodifiableMap(new LinkedHashMap<>(b.errorBreakdown));
        executionOutcomes = Collections.unmodifiableMap(new LinkedHashMap<>(b.executionOutcomes));
        editsPerFiveMinuteWindow = Collections.unmodifiableMap(new LinkedHashMap<>(b.editsPerFiveMinuteWindow));
        events = Collections.unmodifiableList(new ArrayList<>(b.events));
    }

    public static Builder builder(String sessionId, Instant startedAtUtc) {
        return new Builder(sessionId, startedAtUtc);
    }

    public static final class Builder {
        private final String sessionId;
        private final Instant startedAtUtc;
        private String studentId;
        private String projectPath;
        private final Set<String> resolvedProjectPaths = new LinkedHashSet<>();
        private Instant snapshotAtUtc;
        private Instant endedAtUtc;
        private long durationMillis, activeMillis, idleMillis, estimatedNewWords;
        private double compileSuccessRatePercent, estimatedWordsPerActiveMinute;
        private int compileAttempts, compileSucceeded, compileFailed, compileErrors, compileWarnings;
        private Integer firstCompileErrorsOverride, firstCompileWarningsOverride;
        private int firstCompileErrors, firstCompileWarnings;
        private Map<String,Integer> errorBreakdown = Map.of();
        private Map<String,Integer> executionOutcomes = defaultExecutionOutcomes();
        private Map<String,Integer> editsPerFiveMinuteWindow = Map.of();
        private List<TelemetryEvent> events = List.of();

        private Builder(String sessionId, Instant startedAtUtc) {
            this.sessionId = sessionId;
            this.startedAtUtc = startedAtUtc;
        }

        private static Map<String,Integer> defaultExecutionOutcomes() {
            Map<String,Integer> map = new LinkedHashMap<>();
            map.put("NORMAL", 0);
            map.put("EXCEPTION", 0);
            map.put("TERMINATED", 0);
            map.put("UNKNOWN", 0);
            return map;
        }

        public Builder studentId(String value) {
            studentId = value == null || value.isBlank() ? null : value.trim();
            return this;
        }

        public Builder projectPath(String value) {
            if (value != null && !value.isBlank()) {
                projectPath = value;
                resolvedProjectPaths.add(value);
            }
            return this;
        }

        public Builder projectPaths(Collection<String> values) {
            if (values != null) {
                for (String v : values) {
                    if (v != null && !v.isBlank()) resolvedProjectPaths.add(v);
                }
            }
            return this;
        }

        public Builder times(Instant snapshot, Instant ended, long duration, long active, long idle) {
            snapshotAtUtc = snapshot;
            endedAtUtc = ended;
            durationMillis = duration;
            activeMillis = active;
            idleMillis = idle;
            return this;
        }

        public Builder compilations(AnalyticsObserver a) {
            compileAttempts = a.compileAttempts();
            compileSucceeded = a.compileSucceeded();
            compileFailed = a.compileFailed();
            compileErrors = a.errors();
            compileWarnings = a.warnings();
            firstCompileErrorsOverride = a.firstErrors();
            firstCompileWarningsOverride = a.firstWarnings();
            compileSuccessRatePercent = a.successRate();
            errorBreakdown = a.errorsByCategory() == null ? Map.of() : a.errorsByCategory();
            if (a.executionOutcomes() != null && !a.executionOutcomes().isEmpty()) {
                executionOutcomes = a.executionOutcomes();
            }
            return this;
        }

        public Builder editor(long positiveWords, double cadence, Map<String,Integer> edits) {
            estimatedNewWords = positiveWords;
            estimatedWordsPerActiveMinute = Double.isFinite(cadence) ? cadence : 0.0;
            editsPerFiveMinuteWindow = edits == null ? Map.of() : edits;
            return this;
        }

        public Builder events(List<TelemetryEvent> value) {
            events = value == null ? List.of() : value;
            return this;
        }

        public SessionSnapshot build() {
            if (sessionId == null || sessionId.isBlank())
                throw new IllegalStateException("sessionId required");
            if (startedAtUtc == null)
                throw new IllegalStateException("startedAtUtc required");
            if (snapshotAtUtc == null)
                throw new IllegalStateException("snapshot time required");
            if (durationMillis < 0 || activeMillis < 0 || idleMillis < 0 || activeMillis + idleMillis != durationMillis)
                throw new IllegalStateException("active + idle must equal duration");
            if (compileAttempts < 0 || compileSucceeded < 0 || compileFailed < 0 || compileErrors < 0 || compileWarnings < 0)
                throw new IllegalStateException("compilation counts must be non-negative");

            int derivedFirstErrors = 0;
            int derivedFirstWarnings = 0;
            for (TelemetryEvent e : events) {
                if (e == null) continue;
                if (e.project() != null && !e.project().isBlank()) {
                    resolvedProjectPaths.add(e.project());
                }
                if ("COMPILE_ERROR".equals(e.type()) && !Boolean.FALSE.equals(e.details().get("firstInAttempt"))) {
                    derivedFirstErrors++;
                } else if ("COMPILE_WARNING".equals(e.type()) && !Boolean.FALSE.equals(e.details().get("firstInAttempt"))) {
                    derivedFirstWarnings++;
                }
            }
            firstCompileErrors = firstCompileErrorsOverride != null ? firstCompileErrorsOverride : derivedFirstErrors;
            firstCompileWarnings = firstCompileWarningsOverride != null ? firstCompileWarningsOverride : derivedFirstWarnings;
            return new SessionSnapshot(this);
        }
    }
}
