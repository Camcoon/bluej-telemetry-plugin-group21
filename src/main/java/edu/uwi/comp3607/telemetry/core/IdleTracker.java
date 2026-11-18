package edu.uwi.comp3607.telemetry.core;

import java.time.Duration;
import java.time.Instant;

/**
 * Estimates active and idle time from the gaps between recorded events.
 *
 * <p>The tracker treats any quiet span of at least
 * {@link #THRESHOLD_MS} milliseconds as idle. Because a short quiet span may
 * later grow into a long one, idle time is always classified retroactively:
 * completed gaps are accumulated when the next activity arrives, and the
 * still-open gap is evaluated at query time. Idle and active time are mutually
 * exclusive and always sum to the elapsed session duration.</p>
 *
 * <p>Instances are intended to be driven by a single telemetry worker thread;
 * no internal synchronization is performed.</p>
 */
public final class IdleTracker {
    /** Minimum quiet span, in milliseconds, that is classified as idle (150 seconds). */
    public static final long THRESHOLD_MS = 150_000L;

    /** Session start instant; the lower bound for all duration calculations. */
    private final Instant start;

    /** Instant of the most recently recorded activity. */
    private Instant lastActivity;

    /** Sum of quiet spans that were already closed when a later activity arrived. */
    private long completedIdleMillis;

    /**
     * Creates a tracker whose initial activity is the session start.
     *
     * @param start the session start instant
     */
    public IdleTracker(Instant start) {
        this.start = start;
        this.lastActivity = start; }

    /**
     * Records an activity occurrence, closing the preceding quiet span.
     *
     * <p>Earlier or equal timestamps are ignored so that out-of-order callbacks
     * cannot corrupt the accumulated idle total.</p>
     *
     * @param at the instant of the observed activity
     */
    public void activity(Instant at) {
        if (at.isBefore(lastActivity)) return;
        long gap = Duration.between(lastActivity, at).toMillis();
        if (gap >= THRESHOLD_MS) completedIdleMillis += gap;
        lastActivity = at;
    }

    /**
     * Computes the estimated idle time up to the given instant.
     *
     * <p>Includes both completed idle spans and the currently open gap when it
     * already exceeds the threshold, capped at the total elapsed time.</p>
     *
     * @param at the instant to evaluate
     * @return estimated idle time in milliseconds
     */
    public long idleMillis(Instant at) {
        long gap = Math.max(0L, Duration.between(lastActivity, at).toMillis());
        long total = completedIdleMillis + (gap >= THRESHOLD_MS ? gap : 0L);
        return Math.min(total, Math.max(0L, Duration.between(start, at).toMillis()));
    }

    /**
     * Returns the total elapsed time since the session started.
     *
     * @param at the instant to evaluate
     * @return elapsed time in milliseconds, never negative
     */
    public long durationMillis(Instant at) {
        return Math.max(0L, Duration.between(start, at).toMillis()); }

    /**
     * Returns the estimated active time, defined as elapsed time minus idle time.
     *
     * @param at the instant to evaluate
     * @return estimated active time in milliseconds
     */
    public long activeMillis(Instant at) {
        return durationMillis(at) - idleMillis(at); }

    /**
     * Tests whether the given instant falls within the active window following
     * the most recent recorded activity.
     *
     * @param at the instant to evaluate
     * @return {@code true} if the gap since the last activity is below the idle threshold
     */
    public boolean isNonIdle(Instant at) {
        return Math.max(0L, Duration.between(lastActivity, at).toMillis()) < THRESHOLD_MS;
    }
}
