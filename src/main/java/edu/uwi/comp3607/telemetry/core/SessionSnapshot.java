package edu.uwi.comp3607.telemetry.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SessionSnapshot {
    public final String sessionId;
    public final String version;
    public final String studentId;
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
    public final double compileSuccessRatePercent;
    public final long estimatedNewWords;
    public final double estimatedWordsPerActiveMinute;
    public final Map<String, Integer> errorBreakdown;
    public final Map<String, Integer> executionOutcomes;
    public final Map<String, Integer> editsPerFiveMinuteWindow;
    public final List<TelemetryEvent> events;

    private SessionSnapshot(Builder b) {
        sessionId = b.sessionId; version = "1.0.3"; studentId = b.studentId;
        startedAtUtc = b.startedAtUtc; snapshotAtUtc = b.snapshotAtUtc; endedAtUtc = b.endedAtUtc;
        durationMillis = b.durationMillis; activeMillis = b.activeMillis; idleMillis = b.idleMillis;
        compileAttempts = b.compileAttempts; compileSucceeded = b.compileSucceeded;
        compileFailed = b.compileFailed; compileErrors = b.compileErrors;
        compileWarnings = b.compileWarnings; compileSuccessRatePercent = b.compileSuccessRatePercent;
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
        private Instant snapshotAtUtc;
        private Instant endedAtUtc;
        private long durationMillis, activeMillis, idleMillis, estimatedNewWords;
        private double compileSuccessRatePercent, estimatedWordsPerActiveMinute;
        private int compileAttempts, compileSucceeded, compileFailed, compileErrors, compileWarnings;
        private Map<String,Integer> errorBreakdown = Map.of(), executionOutcomes = Map.of(), editsPerFiveMinuteWindow = Map.of();
        private List<TelemetryEvent> events = List.of();
        private Builder(String sessionId, Instant startedAtUtc) {
            this.sessionId = sessionId; this.startedAtUtc = startedAtUtc;
        }
        public Builder studentId(String value) { studentId = value; return this; }
        public Builder times(Instant snapshot, Instant ended, long duration, long active, long idle) {
            snapshotAtUtc = snapshot; endedAtUtc = ended;
            durationMillis = duration; activeMillis = active; idleMillis = idle; return this;
        }
        public Builder compilations(AnalyticsObserver a) {
            compileAttempts = a.compileAttempts(); compileSucceeded = a.compileSucceeded();
            compileFailed = a.compileFailed(); compileErrors = a.errors();
            compileWarnings = a.warnings(); compileSuccessRatePercent = a.successRate();
            errorBreakdown = a.errorsByCategory(); executionOutcomes = a.executionOutcomes();
            return this;
        }
        public Builder editor(long positiveWords, double cadence, Map<String,Integer> edits) {
            estimatedNewWords = positiveWords; estimatedWordsPerActiveMinute = cadence;
            editsPerFiveMinuteWindow = edits; return this;
        }
        public Builder events(List<TelemetryEvent> value) { events = value; return this; }
        public SessionSnapshot build() {
            if (snapshotAtUtc == null) throw new IllegalStateException("snapshot time required");
            if (activeMillis < 0 || idleMillis < 0 || activeMillis + idleMillis != durationMillis)
                throw new IllegalStateException("active + idle must equal duration");
            return new SessionSnapshot(this);
        }
    }
}
