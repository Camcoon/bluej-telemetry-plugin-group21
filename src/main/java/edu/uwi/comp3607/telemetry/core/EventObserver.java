package edu.uwi.comp3607.telemetry.core;

//Observer
@FunctionalInterface
public interface EventObserver {
    void onEvent(TelemetryEvent event);
}
