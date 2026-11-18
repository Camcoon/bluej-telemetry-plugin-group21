package edu.uwi.comp3607.telemetry.bluej;

import bluej.extensions2.BClass;
import bluej.extensions2.BMethod;
import bluej.extensions2.BPackage;
import bluej.extensions2.BProject;
import bluej.extensions2.BlueJ;
import bluej.extensions2.Extension;
import bluej.extensions2.MenuGenerator;
import bluej.extensions2.editor.JavaEditor;
import bluej.extensions2.event.ClassEvent;
import bluej.extensions2.event.ClassListener;
import bluej.extensions2.event.CompileEvent;
import bluej.extensions2.event.CompileListener;
import bluej.extensions2.event.InvocationFinishedEvent;
import bluej.extensions2.event.InvocationFinishedListener;
import bluej.extensions2.event.PackageEvent;
import bluej.extensions2.event.PackageListener;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BlueJ extension entry point that observes editor, class, package, compilation
 * and invocation activity and forwards it to a {@link TelemetryRuntime}.
 *
 * <p>On {@link #startup(BlueJ)} the extension registers listeners for all
 * supported BlueJ events, installs an "Export Telemetry Report" menu item and
 * starts a daemon timer that polls visible Java editors every 30 seconds.
 * Word counts are sampled at most once every five minutes; other editor
 * samples record text length and a content hash so that same-length edits can
 * still be detected.</p>
 *
 * <p><strong>Thread-safety:</strong> BlueJ callbacks and editor polling run on
 * the JavaFX application thread via {@link Platform#runLater(Runnable)}, while
 * all session state lives behind {@link TelemetryRuntime#enqueue(java.util.function.Consumer)}.
 * Every observer method therefore captures an immutable timestamp and forwards
 * a short lambda rather than touching the session directly. BlueJ API calls may
 * fail if a project closes concurrently; most are wrapped and logged so that a
 * single stale event cannot break the extension.</p>
 */
public final class TelemetryExtension extends Extension implements PackageListener, ClassListener,
        CompileListener, InvocationFinishedListener {
    /** The BlueJ instance this extension is attached to; {@code null} after termination. */
    private volatile BlueJ bluej;

    /** Runtime that owns the session; created during startup. */
    private TelemetryRuntime runtime;

    /** Timer used to schedule editor polling on the JavaFX thread. */
    private ScheduledExecutorService pollingTimer;

    /** Cache mapping a package handle to its owning project directory. */
    private final Map<BPackage,String> projectForPackage = new ConcurrentHashMap<>();

    /** Wall-clock timestamp of the last word-count sample. */
    private final AtomicLong lastWordSampleMillis = new AtomicLong(0);

    /** Student id remembered between export dialogs. */
    private volatile String studentId;

    /**
     * Registers listeners, installs the export menu and starts editor polling.
     *
     * @param bluej the host BlueJ instance
     */
    @Override public void startup(BlueJ bluej) {
        this.bluej = bluej;
        runtime = new TelemetryRuntime();
        bluej.addPackageListener(this);
        bluej.addClassListener(this);
        bluej.addCompileListener(this);
        bluej.addInvocationFinishedListener(this);
        bluej.setMenuGenerator(new ExportMenu(this::manualExport));

        pollingTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlueJ-Editor-Poll-Timer"); t.setDaemon(true); return t;
        });
        pollingTimer.scheduleAtFixedRate(() -> {
            try { Platform.runLater(this::pollVisibleJavaEditors); }
            catch (RuntimeException ex) { System.err.println("[telemetry] editor poll skipped: " + ex); }
        }, 30, 30, TimeUnit.SECONDS);
        // Baseline projects which were already open when this extension was loaded.
        try { Platform.runLater(this::baselineAlreadyOpenPackages); }
        catch (RuntimeException ex) { System.err.println("[telemetry] startup baseline unavailable: " + ex); }
    }

    /**
     * Registers every package that was already open when the extension loaded,
     * so that pre-existing projects still produce baseline events.
     */
    private void baselineAlreadyOpenPackages() {
        BlueJ bj = this.bluej;
        if (bj == null) return;
        try {
            for (BProject project : bj.getOpenProjects()) {
                for (BPackage p : project.getPackages()) registerPackage(p, Instant.now());
            }
        } catch (Exception ex) { System.err.println("[telemetry] initial baseline: " + ex); }
    }

    /**
     * Resolves a package's project directory and records the package as opened,
     * including a baseline of its class names.
     *
     * @param pkg the package to register; {@code null} is ignored
     * @param at  the time of the observation
     */
    private void registerPackage(BPackage pkg, Instant at) {
        if (pkg == null) return;
        try {
            String project = normalizeDir(pkg.getProject().getDir());
            if (project == null) return;
            String packageName = pkg.getName();
            if (packageName == null) packageName = "";
            projectForPackage.put(pkg, project);
            List<String> names = new ArrayList<>();
            for (BClass c : pkg.getClasses()) if (c != null) names.add(c.getName());
            runtime.packageOpened(project, packageName, names, at);
        } catch (Exception ex) { System.err.println("[telemetry] cannot register package: " + ex); }
    }

    /**
     * Handles a BlueJ package-open event.
     *
     * @param event the package event
     */
    @Override public void packageOpened(PackageEvent event) { registerPackage(event.getPackage(), Instant.now()); }

    /**
     * Handles a BlueJ package-closing event, clearing the cached project mapping.
     *
     * @param event the package event
     */
    @Override public void packageClosing(PackageEvent event) {
        BPackage pkg = event.getPackage();
        if (pkg == null) return;
        String project = projectForPackage.remove(pkg);
        if (project == null) {
            try { project = normalizeDir(pkg.getProject().getDir()); } catch (Exception ignored) { }
        }
        if (project != null) runtime.packageClosing(project, safePackageName(pkg), Instant.now());
    }

    /**
     * Handles class creation and modification events, sampling the declared
     * method count only for compiled classes.
     *
     * @param event the class event
     */
    @Override public void classStateChanged(ClassEvent event) {
        String project = projectOf(event.getPackage());
        if (project == null) return;
        BClass bc = event.getBClass();
        if (bc == null) return;
        String name = bc.getName();
        if (name == null || name.isBlank()) return;
        Integer methods = null;
        if (event.isClassCompiled()) {
            try {
                BMethod[] declared = bc.getDeclaredMethods();
                if (declared != null) methods = declared.length;
            } catch (Exception ignored) { /* only compiled classes expose methods reliably */ }
        }
        Integer value = methods;
        Instant at = Instant.now();
        runtime.enqueue(s -> s.classChanged(project, name, value, at));
    }

    /**
     * Handles a class rename event, carrying both names to the session.
     *
     * @param event the class event
     */
    @Override public void classNameChanged(ClassEvent event) {
        String project = projectOf(event.getPackage());
        if (project == null) return;
        BClass bc = event.getBClass();
        if (bc == null) return;
        String oldName = event.getOldName(), name = bc.getName();
        if (oldName == null || name == null || oldName.isBlank() || name.isBlank() || oldName.equals(name)) return;
        Instant at = Instant.now();
        runtime.enqueue(s -> s.classRenamed(project, oldName, name, at));
    }

    /**
     * Handles a class deletion event.
     *
     * @param event the class event
     */
    @Override public void classRemoved(ClassEvent event) {
        String project = projectOf(event.getPackage());
        if (project == null) return;
        BClass bc = event.getBClass();
        if (bc == null) return;
        String name = bc.getName();
        if (name == null || name.isBlank()) return;
        Instant at = Instant.now();
        runtime.enqueue(s -> s.classRemoved(project, name, at));
    }

    /**
     * Records the start of a user-triggered compilation; background compiler
     * activity is deliberately ignored.
     *
     * @param ev the compile event
     */
    @Override public void compileStarted(CompileEvent ev) {
        if (!ev.isUserGeneratedCompilation()) return;
        String project = projectOfFiles(ev.getFiles());
        if (project == null) return;
        String files = fileNames(ev.getFiles()); Instant at = Instant.now();
        runtime.enqueue(s -> s.compilationStarted(project, files, at));
    }

    /** {@inheritDoc} */
    @Override public void compileSucceeded(CompileEvent ev) { finishCompile(ev, true); }

    /** {@inheritDoc} */
    @Override public void compileFailed(CompileEvent ev) { finishCompile(ev, false); }

    /**
     * Forwards the outcome of a user-triggered compilation to the session.
     *
     * @param ev         the compile event
     * @param successful whether the compilation succeeded
     */
    private void finishCompile(CompileEvent ev, boolean successful) {
        if (!ev.isUserGeneratedCompilation()) return;
        String project = projectOfFiles(ev.getFiles());
        if (project == null) return;
        String files = fileNames(ev.getFiles()); Instant at = Instant.now();
        runtime.enqueue(s -> s.compilationFinished(project, files, successful, at));
    }

    /** {@inheritDoc} */
    @Override public void compileError(CompileEvent ev) { compilationProblem(ev, false); }

    /** {@inheritDoc} */
    @Override public void compileWarning(CompileEvent ev) { compilationProblem(ev, true); }

    /**
     * Forwards a compiler diagnostic to the session, retaining the raw message
     * and line number so that categories can be derived later.
     *
     * @param ev      the compile event
     * @param warning {@code true} for a warning, {@code false} for an error
     */
    private void compilationProblem(CompileEvent ev, boolean warning) {
        if (!ev.isUserGeneratedCompilation()) return;
        String project = projectOfFiles(ev.getFiles());
        if (project == null) return;
        String file = fileNames(ev.getFiles()), message = ev.getErrorMessage();
        int line = ev.getErrorLineNumber(); Instant at = Instant.now();
        runtime.enqueue(s -> s.compilationProblem(project, file, message, line, warning, at));
    }

    /**
     * Handles method-invocation completion, mapping BlueJ event types to the
     * normalized outcome names used by the report.
     *
     * @param event the invocation event
     */
    @Override public void invocationFinished(InvocationFinishedEvent event) {
        String project = projectOf(event.getPackage());
        if (project == null) return;
        String className = event.getClassName(), method = event.getMethodName();
        String outcome = switch (event.getEventType().name()) {
            case "NORMAL_EXIT" -> "NORMAL";
            case "EXCEPTION_EXIT" -> "EXCEPTION";
            case "TERMINATED_EXIT" -> "TERMINATED";
            default -> "UNKNOWN";
        };
        Instant at = Instant.now();
        runtime.enqueue(s -> s.execution(project, className, method, outcome, at));
    }

    /**
     * Polls all visible Java editors on the JavaFX thread, sampling word counts
     * every five minutes and capturing a content hash on every poll.
     */
    private void pollVisibleJavaEditors() {
        if (bluej == null || runtime == null) return;
        long wallTime = System.currentTimeMillis();
        boolean sampleWords = wallTime - lastWordSampleMillis.get() >= 300_000L;
        if (sampleWords) lastWordSampleMillis.set(wallTime);
        Instant at = Instant.now();
        try {
            for (BProject project : bluej.getOpenProjects()) {
                String root = normalizeDir(project.getDir());
                if (root == null) continue;
                for (BPackage pkg : project.getPackages()) {
                    for (BClass klass : pkg.getClasses()) {
                        try {
                            JavaEditor editor = klass.getJavaEditorIfOpen();
                            if (editor == null || !editor.isVisible()) continue;
                            int size = editor.getTextLength();
                            String content = null;
                            try {
                                var begin = editor.getTextLocationFromOffset(0);
                                var end = editor.getTextLocationFromOffset(size);
                                if (begin != null && end != null) content = editor.getText(begin, end);
                            } catch (RuntimeException ignored) { }
                            Integer words = sampleWords && content != null ? countWords(content) : null;
                            final Integer fingerprint = content == null ? null : content.hashCode();
                            final String name = klass.getName();
                            final Integer sampledWords = words;
                            runtime.enqueue(s -> s.editorSample(root, name, size, sampledWords, fingerprint, at));
                        } catch (Exception ignored) { }
                    }
                }
            }
        } catch (Exception ex) { System.err.println("[telemetry] polling: " + ex); }
    }

    /**
     * Counts whitespace-separated words in editor text.
     *
     * @param text the editor content; may be {@code null} or blank
     * @return the number of words, or zero for empty content
     */
    private static int countWords(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    /**
     * Converts a directory to an absolute, normalized path string.
     *
     * @param dir the directory; may be {@code null}
     * @return the normalized path, or {@code null} if no directory was supplied
     */
    private static String normalizeDir(File dir) {
        return dir == null ? null : dir.toPath().toAbsolutePath().normalize().toString();
    }

    /**
     * Returns the union of cached package project roots and currently open
     * project roots, used as a fallback when an event's package is unavailable.
     *
     * @return the set of known absolute project directories
     */
    private Set<String> knownProjectRoots() {
        Set<String> roots = new HashSet<>(projectForPackage.values());
        BlueJ bj = this.bluej;
        if (bj != null) {
            try {
                for (BProject proj : bj.getOpenProjects()) {
                    if (proj != null) {
                        String root = normalizeDir(proj.getDir());
                        if (root != null) roots.add(root);
                    }
                }
            } catch (Exception ignored) { }
        }
        return roots;
    }

    /**
     * Determines which project a package belongs to, caching the answer. When
     * the package is unknown, a unique open project is used as a fallback.
     *
     * @param pkg the package from a BlueJ event
     * @return the normalized project directory, or {@code null} if ambiguous
     */
    private String projectOf(BPackage pkg) {
        if (pkg != null) {
            String cached = projectForPackage.get(pkg);
            if (cached != null) return cached;
            try {
                String dir = normalizeDir(pkg.getProject().getDir());
                if (dir != null) {
                    projectForPackage.put(pkg, dir);
                    return dir;
                }
            } catch (Exception ignored) { }
        }
        Set<String> openRoots = knownProjectRoots();
        return openRoots.size() == 1 ? openRoots.iterator().next() : null;
    }

    /**
     * Infers the project for a set of files by choosing the longest known root
     * path that is a parent of every matched file. Returns {@code null} when
     * files span multiple projects so that events are not misattributed.
     *
     * @param files the files reported by a compilation event
     * @return the normalized project directory, or {@code null} if ambiguous
     */
    private String projectOfFiles(File[] files) {
        Set<String> roots = knownProjectRoots();
        if (roots.isEmpty()) return null;

        if (files != null) {
            Set<String> matchedProjects = new HashSet<>();
            for (File f : files) {
                if (f == null) continue;
                Path source = f.toPath().toAbsolutePath().normalize();
                String bestRoot = null;
                int bestNameCount = -1;
                for (String root : roots) {
                    Path candidate = Path.of(root).toAbsolutePath().normalize();
                    if (source.startsWith(candidate) && candidate.getNameCount() > bestNameCount) {
                        bestRoot = root;
                        bestNameCount = candidate.getNameCount();
                    }
                }
                if (bestRoot != null) {
                    matchedProjects.add(bestRoot);
                }
            }
            if (matchedProjects.size() == 1) {
                return matchedProjects.iterator().next();
            }
            if (matchedProjects.size() > 1) {
                return null;
            }
        }

        return roots.size() == 1 ? roots.iterator().next() : null;
    }

    /**
     * Joins the simple names of a file array into a comma-separated list.
     *
     * @param files the files; may be {@code null} or empty
     * @return the joined names, or {@code null} if there are none
     */
    private static String fileNames(File[] files) {
        if (files == null || files.length == 0) return null;
        List<String> names = new ArrayList<>();
        for (File file : files) if (file != null) names.add(file.getName());
        return String.join(", ", names);
    }

    /**
     * Reads a package name defensively for logging purposes.
     *
     * @param pkg the package handle
     * @return the package name, or {@code "unknown"} on failure
     */
    private static String safePackageName(BPackage pkg) {
        try { return pkg.getName(); } catch (Exception ex) { return "unknown"; }
    }

    /**
     * Prompts for a student id and triggers a full JSON and PDF export.
     *
     * @param pkg the package whose project should be exported
     */
    private void manualExport(BPackage pkg) {
        String project = projectOf(pkg);
        if (project == null) {
            new Alert(Alert.AlertType.ERROR, "No open BlueJ project to export.").show(); return;
        }
        TextInputDialog dialog = new TextInputDialog(studentId == null ? "" : studentId);
        dialog.setTitle("Telemetry report");
        dialog.setHeaderText("Student ID for this BlueJ session");
        dialog.setContentText("Student ID (blank keeps JSON null):");
        dialog.showAndWait().ifPresent(id -> {
            studentId = id.isBlank() ? null : id.trim().substring(0, Math.min(64, id.trim().length()));
            runtime.manualExport(project, studentId,
                    dir -> Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                            "JSON and two-page PDF exported to:\n" + dir).show()),
                    ex -> Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                            "Telemetry export failed:\n" + ex.getMessage()).show()));
        });
    }

    /** Stops polling, finishes the runtime session and releases the BlueJ handle. */
    @Override public void terminate() {
        if (pollingTimer != null) pollingTimer.shutdownNow();
        if (runtime != null) runtime.terminate();
        bluej = null;
    }

    /** {@inheritDoc} */
    @Override public boolean isCompatible() { return getExtensionsAPIVersionMajor() >= 3; }

    /** {@inheritDoc} */
    @Override public String getName() { return "BlueJ Telemetry"; }

    /** {@inheritDoc} */
    @Override public String getVersion() { return "1.0.3"; }

    /** {@inheritDoc} */
    @Override public String getDescription() { return "Collect estimated editor activity and BlueJ lifecycle, compile and run events."; }

    /**
     * Returns the project page for this extension.
     *
     * @return the project URL, or {@code null} if the URL is malformed
     */
    @Override public URL getURL() {
        try {
            return new URL("https://github.com/Camcoon/bluej-telemetry-plugin-group21");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Menu generator that wires the Tools menu item to the export action. */
    private static final class ExportMenu extends MenuGenerator {
        /** Callback invoked with the package that was active when the menu was built. */
        private final java.util.function.Consumer<BPackage> action;

        /**
         * Creates the menu generator.
         *
         * @param action the export callback
         */
        private ExportMenu(java.util.function.Consumer<BPackage> action) { this.action = action; }

        /**
         * Builds the export menu item.
         *
         * @param pkg the package associated with the menu
         * @return the configured menu item
         */
        @Override public MenuItem getToolsMenuItem(BPackage pkg) {
            MenuItem item = new MenuItem("Export Telemetry Report (JSON + PDF)");
            item.setOnAction(e -> action.accept(pkg));
            return item;
        }
    }
}
