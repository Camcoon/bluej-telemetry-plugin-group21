package edu.uwi.comp3607.telemetry.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AnalyticsObserver implements EventObserver {
    private int compileAttempts;
    private int compileSucceeded;
    private int compileFailed;
    private int errors;
    private int warnings;
    private final Map<String, Integer> errorsByCategory = new LinkedHashMap<>();
    private final Map<String, Integer> executionOutcomes = new LinkedHashMap<>();

    public AnalyticsObserver() {
        for (String outcome : new String[]{"NORMAL", "EXCEPTION", "TERMINATED", "UNKNOWN"}) {
            executionOutcomes.put(outcome, 0);
        }
    }

    @Override public void onEvent(TelemetryEvent event) {
        switch (event.type()) {
            case "COMPILE_STARTED" -> compileAttempts++;
            case "COMPILE_SUCCEEDED" -> compileSucceeded++;
            case "COMPILE_FAILED" -> compileFailed++;
            case "COMPILE_ERROR" -> {
                errors++;
                String code = String.valueOf(event.details().getOrDefault("errorCode", "OTHER"));
                errorsByCategory.merge(code, 1, Integer::sum);
            }
            case "COMPILE_WARNING" -> warnings++;
            case "EXECUTION_FINISHED" -> {
                String kind = String.valueOf(event.details().getOrDefault("outcome", "UNKNOWN"));
                executionOutcomes.merge(kind, 1, Integer::sum);
            }
            default -> { }
        }
    }

    public int compileAttempts() { return compileAttempts; }
    public int compileSucceeded() { return compileSucceeded; }
    public int compileFailed() { return compileFailed; }
    public int errors() { return errors; }
    public int warnings() { return warnings; }
    public Map<String, Integer> errorsByCategory() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(errorsByCategory)); }
    public Map<String, Integer> executionOutcomes() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(executionOutcomes)); }
    public double successRate() {
        int completed = compileSucceeded + compileFailed;
        return completed == 0 ? 0.0 : compileSucceeded * 100.0 / completed;
    }
}
