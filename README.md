# BlueJ Telemetry Plugin — Group 21

**UWI COMP 3607 Object Oriented Programming II — 2026/2027 Semester 1**

A Java 17 / Maven extension that collects estimated coding activity and observed BlueJ lifecycle, compilation, and invocation events. The official project repository is the Group 21 GitHub repository linked below.

## What the extension does

- Records one in-memory telemetry session per extension startup (normally one BlueJ launch), even when multiple projects are open.
- Uses package callbacks to infer project opens and closes; observes class changes, removals and renames. Creation is inferred after baseline registration. Method counts are sampled from compiled Java classes.
- Tracks user-generated compilation attempts, success/failure and the first accessible error/warning of each attempt. The `errorCode` is a derived message category, not a numeric code supplied by BlueJ.
- Records completed invocations as normal, exception, terminated or unknown, with available class and method names.
- Polls visible Java editor text lengths every 30 seconds; samples word counts every five minutes. Estimates edits per five-minute window, positive net new words and typing cadence from these samples.
- Retroactively classifies a complete quiet interval as idle after 150 seconds with no detected activity. Active time is session duration less estimated idle time.
- Autosaves JSON approximately every minute and exports JSON plus a two-page PDF through Tools-menu manual export, final package-close, and extension shutdown.

**Limits in the demo:** The extension does not reliably detect project creation or deletion, same-length edits, exact newly typed words, individual keystrokes, raw console output, or stack traces. Event-level project paths help distinguish projects, but this is a launch-wide session. Counts and cadence are estimates, not a complete record of all editing.

## Installation and build (Windows PowerShell)

Prerequisites: **JDK 17**, Apache Maven, **BlueJ 6.0.1** with its `bluej.jar` available. Use the BlueJ installation path on your machine.

```powershell
mvn install:install-file -Dfile="C:\Program Files\BlueJ\lib\bluej.jar" "-DgroupId=org.bluej" "-DartifactId=bluej" "-Dversion=6.0.1" "-Dpackaging=jar"
mvn clean verify
```

`mvn clean verify` is the required local test/coverage check.

Copy that JAR into either `MyBlueJProject\extensions2\` (project-specific) or `%USERPROFILE%\bluej\extensions2\` (user-wide on Windows), and restart BlueJ.

In BlueJ, open a project and use **Tools → Export Telemetry Report (JSON + PDF)**. You may enter a Student ID during manual export; the ID stays in memory for the rest of the BlueJ session. Before entry, the ID is JSON `null` and PDF `NULL`.

## Output files

```text
MyBlueJProject/
├── package.bluej
├── MyClass.java
└── Telemetry-Report/
    ├── telemetry-session.json
    └── telemetry-report.pdf
```

Full exports also create timestamped backups under `<user-home>/.bluej-telemetry/backups/`, outside the project. JSON includes timestamps, session metadata, events, editor activity and invocation outcomes. The PDF has two pages of session statistics, charts, an error table and a project/class/method-count timeline.

## Architecture and quality

| Design pattern | Implementation | Purpose |
|---|---|---|
| Observer | `EventObserver`, `AnalyticsObserver`, `TelemetrySession` | Update analytics as telemetry events are recorded |
| Builder | `SessionSnapshot.Builder` | Build immutable report input with validated time totals |
| Strategy | `ReportWriter`, `JsonReportWriter`, `PdfReportWriter` | Select a JSON or PDF output algorithm |

`EventFactory` creates normalized events. A background worker serializes session-state updates and exports; periodic tasks schedule polling/autosaves. Components are separated by collection (`bluej`), state/analytics (`core`) and formatting/export (`export`).

- Tests: `src/test/java/edu/uwi/comp3607/telemetry/TelemetryCoreTest.java` (JUnit 5 source tests).
- Maven Surefire is configured to execute JUnit tests.
- JaCoCo's configured 75% line-coverage gate excludes the `bluej` integration package.
- A configured gate is not an actual passing coverage measurement: check `mvn clean verify` and `target/site/jacoco/index.html`.

## Project documentation and sources

- [BlueJ Extensions API and installation documentation](https://www.bluej.org/extensions/writingextensions2.html)

**Project homepage:** https://github.com/Camcoon/bluej-telemetry-plugin-group21
