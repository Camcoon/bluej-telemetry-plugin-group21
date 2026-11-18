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

/**
 * Immutable, report-ready view of a telemetry session.
 *
 * <p>Instances are built exclusively through {@link #builder(String, Instant)}.
 * All collections are defensively copied and exposed as unmodifiable views, so
 * a snapshot remains stable while the underlying session continues to change.
 * Any derived metric that is an estimate (idle time, word counts, error
 * categories) is labelled as such by the report writers.</p>
 *
 * <p>Instances are safe to share between threads after construction.</p>
 */
public final class SessionSnapshot {
    /** Unique identifier of the session. */
    public final String sessionId;

    /** Report schema / plugin version used to produce this snapshot. */
    public final String version;

    /** Student identifier supplied at export time, or {@code null}. */
    public final String studentId;

    /** Single project path, or {@code null} when no unique project could be identified. */
    public final String projectPath;

    /** All observed project paths, in first-seen order. */
    public final List<String> projectPaths;

    /** Time at which the session started. */
    public final Instant startedAtUtc;

    /** Time at which the snapshot was taken. */
    public final Instant snapshotAtUtc;

    /** Time at which collection stopped, or {@code null} for a live session. */
    public final Instant endedAtUtc;

    /** Total elapsed session time in milliseconds. */
    public final long durationMillis;

    /** Estimated active coding time in milliseconds. */
    public final long activeMillis;

    /** Estimated idle time in milliseconds. */
    public final long idleMillis;

    /** Number of observed compilation attempts. */
    public final int compileAttempts;

    /** Number of observed successful compilations. */
    public final int compileSucceeded;

    /** Number of observed failed compilations. */
    public final int compileFailed;

    /** Total number of observed error diagnostics. */
    public final int compileErrors;

    /** Total number of observed warning diagnostics. */
    public final int compileWarnings;

    /** Number of errors that were first in their compilation attempt. */
    public final int firstCompileErrors;

    /** Number of warnings that were first in their compilation attempt. */
    public final int firstCompileWarnings;

    /** Success percentage across completed compilations. */
    public final double compileSuccessRatePercent;

    /** Estimated count of positive net new words written while active. */
    public final long estimatedNewWords;

    /** Estimated writing cadence in words per active minute. */
    public final double estimatedWordsPerActiveMinute;

    /** Counts of error diagnostics by derived category. */
    public final Map<String, Integer> errorBreakdown;

    /** Counts of program executions by normalized outcome. */
    public final Map<String, Integer> executionOutcomes;

    /** Edit counts keyed by five-minute window index since the session started. */
    public final Map<String, Integer> editsPerFiveMinuteWindow;

    /** All recorded events in their original order. */
    public final List<TelemetryEvent> events;

    /**
     * Creates a snapshot by copying all values out of the supplied builder.
     *
     * @param b the fully configured builder
     */
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

    /**
     * Starts a new snapshot builder.
     *
     * @param sessionId    the unique session identifier; required by {@link Builder#build()}
     * @param startedAtUtc the session start time; required by {@link Builder#build()}
     * @return a new builder
     */
    public static Builder builder(String sessionId, Instant startedAtUtc) {
        return new Builder(sessionId, startedAtUtc);
    }

    /**
     * Fluent builder for {@link SessionSnapshot}.
     *
     * <p>Only the session identifier, start time and snapshot time are
     * mandatory; all other sections have neutral defaults. Project paths are
     * additionally harvested from recorded events during
     * {@link #build()}, so callers need not enumerate them explicitly.</p>
     */
    public static final class Builder {
        /** Unique session identifier. */
        private final String sessionId;

        /** Session start time. */
        private final Instant startedAtUtc;

        /** Optional student identifier. */
        private String studentId;

        /** Optional single project path. */
        private String projectPath;

        /** Union of explicitly supplied and event-derived project paths. */
        private final Set<String> resolvedProjectPaths = new LinkedHashSet<>();

        /** Snapshot time, validated as non-null by {@link #build()}. */
        private Instant snapshotAtUtc;

        /** Optional session end time. */
        private Instant endedAtUtc;

        /** Elapsed, active and idle time in milliseconds. */
        private long durationMillis, activeMillis, idleMillis, estimatedNewWords;

        /** Compilation success rate and estimated words-per-active-minute. */
        private double compileSuccessRatePercent, estimatedWordsPerActiveMinute;

        /** Aggregate compilation counters. */
        private int compileAttempts, compileSucceeded, compileFailed, compileErrors, compileWarnings;

        /** Optional overrides for first-in-attempt diagnostic counts. */
        private Integer firstCompileErrorsOverride, firstCompileWarningsOverride;

        /** Resolved first-in-attempt diagnostic counts. */
        private int firstCompileErrors, firstCompileWarnings;

        /** Error counts by derived category. */
        private Map<String,Integer> errorBreakdown = Map.of();

        /** Execution counts by outcome. */
        private Map<String,Integer> executionOutcomes = defaultExecutionOutcomes();

        /** Edit counts by five-minute window. */
        private Map<String,Integer> editsPerFiveMinuteWindow = Map.of();

        /** Recorded events. */
        private List<TelemetryEvent> events = List.of();

        /**
         * Creates a builder for the given session.
         *
         * @param sessionId    the unique session identifier
         * @param startedAtUtc the session start time
         */
        private Builder(String sessionId, Instant startedAtUtc) {
            this.sessionId = sessionId;
            this.startedAtUtc = startedAtUtc;
        }

        /**
         * Creates the default outcome map with zero counts for all known outcomes.
         *
         * @return a new insertion-ordered map
         */
        private static Map<String,Integer> defaultExecutionOutcomes() {
            Map<String,Integer> map = new LinkedHashMap<>();
            map.put("NORMAL", 0);
            map.put("EXCEPTION", 0);
            map.put("TERMINATED", 0);
            map.put("UNKNOWN", 0);
            return map;
        }

        /**
         * Sets the student identifier, normalizing blank values to {@code null}.
         *
         * @param value the raw identifier
         * @return this builder
         */
        public Builder studentId(String value) {
            studentId = value == null || value.isBlank() ? null : value.trim();
            return this;
        }

        /**
         * Sets the single project path and adds it to the resolved path set.
         *
         * @param value the project path; blank values are ignored
         * @return this builder
         */
        public Builder projectPath(String value) {
            if (value != null && !value.isBlank()) {
                projectPath = value;
                resolvedProjectPaths.add(value);
            }
            return this;
        }

        /**
         * Adds all non-blank project paths to the resolved path set.
         *
         * @param values the project paths; may be {@code null}
         * @return this builder
         */
        public Builder projectPaths(Collection<String> values) {
            if (values != null) {
                for (String v : values) {
                    if (v != null && !v.isBlank()) resolvedProjectPaths.add(v);
                }
            }
            return this;
        }

        /**
         * Sets timing information for the snapshot.
         *
         * @param snapshot the time the snapshot was taken
         * @param ended    the session end time, or {@code null} if still running
         * @param duration total elapsed milliseconds
         * @param active   estimated active milliseconds
         * @param idle     estimated idle milliseconds
         * @return this builder
         */
        public Builder times(Instant snapshot, Instant ended, long duration, long active, long idle) {
            snapshotAtUtc = snapshot;
            endedAtUtc = ended;
            durationMillis = duration;
            activeMillis = active;
            idleMillis = idle;
            return this;
        }

        /**
         * Copies aggregate statistics from an {@link AnalyticsObserver}.
         *
         * @param a the observer to read; must not be {@code null}
         * @return this builder
         */
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

        /**
         * Sets editor activity estimates.
         *
         * @param positiveWords the estimated positive net new words
         * @param cadence       the estimated words per active minute; non-finite values become zero
         * @param edits         edit counts by five-minute window; may be {@code null}
         * @return this builder
         */
        public Builder editor(long positiveWords, double cadence, Map<String,Integer> edits) {
            estimatedNewWords = positiveWords;
            estimatedWordsPerActiveMinute = Double.isFinite(cadence) ? cadence : 0.0;
            editsPerFiveMinuteWindow = edits == null ? Map.of() : edits;
            return this;
        }

        /**
         * Sets the recorded events.
         *
         * @param value the events in recording order; may be {@code null}
         * @return this builder
         */
        public Builder events(List<TelemetryEvent> value) {
            events = value == null ? List.of() : value;
            return this;
        }

        /**
         * Validates the configured values and creates the immutable snapshot.
         *
         * <p>Event project paths are added to the resolved set, and
         * first-in-attempt diagnostic counts are derived from events when the
         * analytics observer did not supply an override.</p>
         *
         * @return the immutable snapshot
         * @throws IllegalStateException if a required value is missing, timing
         *                               values are negative, active plus idle time
         *                               does not equal the duration, or compilation
         *                               counters are negative
         */
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
