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

/**
 * Owns the telemetry session and marshals all access to it onto a worker thread.
 *
 * <p>BlueJ callbacks may arrive on the JavaFX application thread, while the
 * periodic autosave timer runs on a separate scheduler. Both funnel their work
 * through {@link #enqueue(Consumer)}, which serializes every session mutation
 * and snapshot on a single daemon worker. A 60-second timer writes JSON
 * autosaves for every observed project; {@link #terminate()} finishes the
 * session, performs a final full export and waits up to 20 seconds for it to
 * complete.</p>
 *
 * <p>All methods are safe to call from any thread. Callbacks supplied to
 * {@link #manualExport(String, String, Consumer, Consumer)} are invoked on the
 * worker thread and should hand UI work back to the JavaFX thread themselves.</p>
 */
public final class TelemetryRuntime {
    /** The single session owned by this runtime. */
    private final TelemetrySession session = new TelemetrySession(Instant.now());

    /** Exporter used for autosaves, manual exports and shutdown exports. */
    private final ExportService exports = new ExportService();

    /** Single-threaded executor that serializes all session access. */
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
        Thread t = new Thread(runnable, "BlueJ-Telemetry-Worker"); t.setDaemon(true); return t;
    });

    /** Timer that periodically enqueues JSON autosave work. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread t = new Thread(runnable, "BlueJ-Telemetry-Timer"); t.setDaemon(true); return t;
    });

    /** Set once termination begins, after which enqueued work is ignored. */
    private volatile boolean terminated;

    /**
     * Creates the runtime and starts the periodic autosave timer.
     */
    public TelemetryRuntime() {
        scheduler.scheduleAtFixedRate(() -> enqueue(s -> {
            for (Path p : s.projectDirectories()) {
                try { exports.exportJsonAutosave(s.snapshot(Instant.now()), p); }
                catch (Exception ex) { System.err.println("[telemetry] autosave failed: " + ex); }
            }
        }), 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Schedules a unit of work to run against the session on the worker thread.
     *
     * <p>Callbacks that throw are logged and swallowed so that one bad event
     * cannot kill the worker. Work submitted after termination is discarded.</p>
     *
     * @param callback the operation to perform on the session
     */
    public void enqueue(Consumer<TelemetrySession> callback) {
        if (terminated) return;
        try { worker.execute(() -> {
            try { callback.accept(session); }
            catch (Exception ex) { System.err.println("[telemetry] event failed: " + ex); }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    /**
     * Records that a package was opened.
     *
     * @param project the normalized project directory
     * @param pkg     the package name
     * @param classes the class names in the package baseline
     * @param at      the time of the observation
     */
    public void packageOpened(String project, String pkg, List<String> classes, Instant at) {
        enqueue(s -> s.openPackage(project, pkg, classes, at));
    }

    /**
     * Records that a package closed, exporting the full report when it was the
     * project's last open package.
     *
     * @param project the normalized project directory
     * @param pkg     the package name
     * @param at      the time of the observation
     */
    public void packageClosing(String project, String pkg, Instant at) {
        enqueue(s -> {
            if (s.closePackage(project, pkg, at)) exportTo(s, Path.of(project));
        });
    }

    /**
     * Sets the student id and exports a full report on the worker thread.
     *
     * @param project   the normalized project directory
     * @param studentId the student id to store, or {@code null}
     * @param onSuccess callback receiving the report directory; may be {@code null}
     * @param onFailure callback receiving any export error; may be {@code null}
     */
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

    /**
     * Performs a full export for a project, logging but not rethrowing failures.
     *
     * @param s the session to export
     * @param p the project directory
     */
    private void exportTo(TelemetrySession s, Path p) {
        try { exports.exportAll(s.snapshot(Instant.now()), p); }
        catch (Exception ex) { System.err.println("[telemetry] export failed for " + p + ": " + ex); }
    }

    /**
     * Stops the timers, finishes the session and waits for a final export of
     * every observed project. Subsequent calls have no effect.
     */
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
