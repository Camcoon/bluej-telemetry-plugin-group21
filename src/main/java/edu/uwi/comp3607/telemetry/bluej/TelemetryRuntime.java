package edu.uwi.comp3607.telemetry.bluej;

import edu.uwi.comp3607.telemetry.core.SessionSnapshot;
import edu.uwi.comp3607.telemetry.core.TelemetrySession;
import edu.uwi.comp3607.telemetry.export.ExportService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class TelemetryRuntime {
    private final TelemetrySession session = new TelemetrySession(Instant.now());
    private final ExportService exports = new ExportService();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
        Thread t = new Thread(runnable, "BlueJ-Telemetry-Worker"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread t = new Thread(runnable, "BlueJ-Telemetry-Timer"); t.setDaemon(true); return t;
    });
    private volatile boolean terminated;

    public TelemetryRuntime() {
        scheduler.scheduleAtFixedRate(() -> enqueue(s -> {
            for (Path p : s.projectDirectories()) {
                try { exports.exportJsonAutosave(s.snapshot(Instant.now()), p); }
                catch (Exception ex) { System.err.println("[telemetry] autosave failed: " + ex); }
            }
        }), 60, 60, TimeUnit.SECONDS);
    }

    public void enqueue(Consumer<TelemetrySession> callback) {
        if (terminated) return;
        try { worker.execute(() -> {
            try { callback.accept(session); }
            catch (Exception ex) { System.err.println("[telemetry] event failed: " + ex); }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    public void packageOpened(String project, String pkg, List<String> classes, Instant at) {
        enqueue(s -> s.openPackage(project, pkg, classes, at));
    }
    public void packageClosing(String project, String pkg, Instant at) {
        enqueue(s -> {
            if (s.closePackage(project, pkg, at)) exportTo(s, Path.of(project));
        });
    }
    public void manualExport(String project, String studentId, Consumer<Path> onSuccess,
                             Consumer<Exception> onFailure) {
        enqueue(s -> {
            try {
                s.setStudentId(studentId);
                Path path = exports.exportAll(s.snapshot(Instant.now()), Path.of(project));
                if (onSuccess != null) onSuccess.accept(path);
            } catch (Exception ex) {
                if (onFailure != null) onFailure.accept(ex);
                System.err.println("[telemetry] manual export failed: " + ex);
            }
        });
    }
    private void exportTo(TelemetrySession s, Path p) {
        try { exports.exportAll(s.snapshot(Instant.now()), p); }
        catch (Exception ex) { System.err.println("[telemetry] export failed for " + p + ": " + ex); }
    }

    public void terminate() {
        if (terminated) return;
        terminated = true;
        scheduler.shutdownNow();
        try {
            Future<?> finished = worker.submit(() -> {
                session.finish(Instant.now());
                SessionSnapshot snapshot = session.snapshot(Instant.now());
                for (Path project : session.projectDirectories()) {
                    try { exports.exportAll(snapshot, project); }
                    catch (Exception ex) { System.err.println("[telemetry] shutdown export failed: " + ex); }
                }
            });
            finished.get(20, TimeUnit.SECONDS);
        } catch (Exception ex) { System.err.println("[telemetry] shutdown timed out/interrupted: " + ex); }
        finally { worker.shutdown(); }
    }
}
