package edu.uwi.comp3607.telemetry.core;

/**
 * Observer callback for consuming telemetry events as they are recorded.
 *
 * <p>Observers are registered with a {@link TelemetrySession} and are notified
 * synchronously, in recording order, whenever a new {@link TelemetryEvent} is
 * accepted. Implementations should be fast and must not mutate the session
 * from within the callback: notification happens on the same telemetry worker
 * thread that records the event.</p>
 */
@FunctionalInterface
public interface EventObserver {
    /**
     * Receives a newly recorded telemetry event.
     *
     * @param event the immutable event that was just recorded
     */
    void onEvent(TelemetryEvent event);
}
