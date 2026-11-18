package edu.uwi.comp3607.telemetry.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumulates aggregate compilation and execution statistics from the event stream.
 *
 * <p>This observer is installed automatically by every {@link TelemetrySession}
 * and provides the numbers used by {@link SessionSnapshot.Builder#compilations(AnalyticsObserver)}.
 * Counts are derived from observed events only: an event that is never recorded
 * (for example a compilation triggered outside BlueJ) is never counted.</p>
 *
 * <p>All state is mutated from the session's telemetry worker thread. Map
 * accessors return unmodifiable copies so callers cannot alter the totals.</p>
 */
public final class AnalyticsObserver implements EventObserver {
    /** Number of {@code COMPILE_STARTED} events observed. */
    private int compileAttempts;

    /** Number of {@code COMPILE_SUCCEEDED} events observed. */
    private int compileSucceeded;

    /** Number of {@code COMPILE_FAILED} events observed. */
    private int compileFailed;

    /** Total number of {@code COMPILE_ERROR} diagnostics observed. */
    private int errors;

    /** Total number of {@code COMPILE_WARNING} diagnostics observed. */
    private int warnings;

    /** Errors flagged as the first diagnostic of their compilation attempt. */
    private int firstErrors;

    /** Warnings flagged as the first diagnostic of their compilation attempt. */
    private int firstWarnings;

    /** Error counts keyed by the category derived by {@link EventFactory#errorCategory(String)}. */
    private final Map<String, Integer> errorsByCategory = new LinkedHashMap<>();

    /** Execution counts keyed by normalized outcome; always contains the four known outcomes. */
    private final Map<String, Integer> executionOutcomes = new LinkedHashMap<>();

    /**
     * Creates an observer with all counters at zero and the four known
     * execution outcomes pre-populated with zero counts.
     */
    public AnalyticsObserver() {
        for (String outcome : new String[]{"NORMAL", "EXCEPTION", "TERMINATED", "UNKNOWN"}) {
            executionOutcomes.put(outcome, 0);
        }
    }

    /**
     * Updates aggregate counters for compilation and execution events.
     *
     * <p>Other event types are ignored. First-in-attempt diagnostics are
     * detected through the {@code firstInAttempt} detail flag, defaulting to
     * {@code true} when the flag is absent for compatibility with older events.</p>
     *
     * @param event the event to incorporate; must not be {@code null}
     */
    @Override public void onEvent(TelemetryEvent event) {
        switch (event.type()) {
            case "COMPILE_STARTED" -> compileAttempts++;
            case "COMPILE_SUCCEEDED" -> compileSucceeded++;
            case "COMPILE_FAILED" -> compileFailed++;
            case "COMPILE_ERROR" -> {
                errors++;
                if (!Boolean.FALSE.equals(event.details().get("firstInAttempt"))) {
                    firstErrors++;
                }
                String code = String.valueOf(event.details().getOrDefault("errorCode", "OTHER"));
                errorsByCategory.merge(code, 1, Integer::sum);
            }
            case "COMPILE_WARNING" -> {
                warnings++;
                if (!Boolean.FALSE.equals(event.details().get("firstInAttempt"))) {
                    firstWarnings++;
                }
            }
            case "EXECUTION_FINISHED" -> {
                String kind = String.valueOf(event.details().getOrDefault("outcome", "UNKNOWN"));
                executionOutcomes.merge(kind, 1, Integer::sum);
            }
            default -> { }
        }
    }

    /** @return the number of observed compilation starts */
    public int compileAttempts() { return compileAttempts; }

    /** @return the number of observed successful compilations */
    public int compileSucceeded() { return compileSucceeded; }

    /** @return the number of observed failed compilations */
    public int compileFailed() { return compileFailed; }

    /** @return the total number of observed error diagnostics */
    public int errors() { return errors; }

    /** @return the total number of observed warning diagnostics */
    public int warnings() { return warnings; }

    /** @return the number of errors that were first in their compilation attempt */
    public int firstErrors() { return firstErrors; }

    /** @return the number of warnings that were first in their compilation attempt */
    public int firstWarnings() { return firstWarnings; }

    /**
     * Returns error counts by derived category.
     *
     * @return an unmodifiable insertion-ordered copy of the category counts
     */
    public Map<String, Integer> errorsByCategory() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(errorsByCategory)); }

    /**
     * Returns execution counts by normalized outcome.
     *
     * @return an unmodifiable copy containing at least the four known outcomes
     */
    public Map<String, Integer> executionOutcomes() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(executionOutcomes)); }

    /**
     * Computes the success rate over completed compilations only.
     *
     * @return a percentage in the range {@code 0.0} to {@code 100.0}, or {@code 0.0} if none completed
     */
    public double successRate() {
        int completed = compileSucceeded + compileFailed;
        return completed == 0 ? 0.0 : compileSucceeded * 100.0 / completed;
    }
}
