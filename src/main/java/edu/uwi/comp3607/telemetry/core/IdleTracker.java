package edu.uwi.comp3607.telemetry.core;

import java.time.Duration;
import java.time.Instant;

public final class IdleTracker {
    public static final long THRESHOLD_MS = 150_000L;
    private final Instant start;
    private Instant lastActivity;
    private long completedIdleMillis;

    public IdleTracker(Instant start) {
        this.start = start;
        this.lastActivity = start; }

    public void activity(Instant at) {
        if (at.isBefore(lastActivity)) return;
        long gap = Duration.between(lastActivity, at).toMillis();
        if (gap >= THRESHOLD_MS) completedIdleMillis += gap;
        lastActivity = at;
    }

    public long idleMillis(Instant at) {
        long gap = Math.max(0L, Duration.between(lastActivity, at).toMillis());
        long total = completedIdleMillis + (gap >= THRESHOLD_MS ? gap : 0L);
        return Math.min(total, Math.max(0L, Duration.between(start, at).toMillis()));
    }

    public long durationMillis(Instant at) {
        return Math.max(0L, Duration.between(start, at).toMillis()); }

    public long activeMillis(Instant at) {
        return durationMillis(at) - idleMillis(at); }

    public boolean isNonIdle(Instant at) {
        return Math.max(0L, Duration.between(lastActivity, at).toMillis()) < THRESHOLD_MS;
    }
}
