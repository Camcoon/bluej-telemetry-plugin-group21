package edu.uwi.comp3607.telemetry.core;

@FunctionalInterface
public interface EventObserver {
    void onEvent(TelemetryEvent event);
}
