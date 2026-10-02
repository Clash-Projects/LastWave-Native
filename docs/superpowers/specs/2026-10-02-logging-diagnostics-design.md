# Logging & Diagnostics Redesign — Design Spec

Date: 2026-10-02
Status: Approved — implementing as 3 stacked PRs (§13)
Scope: `com.lastwave.app` (Android, Kotlin, targetSdk 35)

> **minSdk is not fixed at 29.** `app/build.gradle.kts` declares `minSdk = 29`
> by default but honours a `-PminSdk` override, and
> `.github/workflows/build.yml:118` builds a legacy APK with `-PminSdk=24`.
> All new code must therefore compile and run against **API 24**. Every
> API-gated call needs an explicit version check, and the version checks must
> cover the platform level CI actually builds, not just the default.

---

## 1. Problem

Logging today is 382 raw `android.util.Log` calls across 39 files with 35
ad-hoc string tags, no verbosity control, and no abstraction. On disk there
are only two files:

- `dataDir/lastwave_crash_guard.log` — **truncates** at 128 KB, destroying all
  history rather than rotating. One file, unbounded entries, no separation
  between distinct crashes.
- `dataDir/lastwave_startup_trail.log` — keeps only the last **2** launches.

`PlaybackDiagnostics` is a half-built subsystem: `snapshot()` and `clear()` have
zero callers, and 5 of its 6 counters are declared but never incremented.

The user-facing export (`SettingsViewModel.exportDiagnostics`) shells out to
`logcat -d --pid=<self>`. Because it only ever reads the **current** process,
it structurally cannot report the crash that terminated the previous run. There
is no ANR detection, no native-crash capture, and no low-memory-kill capture —
so the Android 16 RenderThread native crash recorded in
`docs/liquid-glass-migration.md` had no LastWave stack trace available and
could not be diagnosed.

There are no tests and no documentation for any of it.

## 2. Goals

1. **Two separate, well-formed log channels.** A crash channel that survives
   process death and retains the **10 most recent distinct crashes**; an
   operational channel with bounded size.
2. **Detect every failure class reachable from inside the app process**,
   including the ones that produce no Java stack trace at all.
3. **A report that is readable by a human**, not a raw logcat dump.
4. **An in-app viewer** so logs can be read, filtered, and searched without adb.
5. **Never block an audio thread.** Logging is called from the real-time audio
   path; a disk write on that thread would manufacture the underruns the app
   is trying to detect.

## 3. Non-goals

- No remote crash upload. Out of scope: it needs a backend, a privacy review,
  and network consent UX. Logs are shared manually via the system share sheet,
  matching the existing export flow.
- No new Gradle dependencies. Pure Kotlin + `android.*` only.
- No changes to the native C++ sources. Oboe and the USB driver already log via
  `__android_log_print`; those lines are surfaced through the logcat section.
- `Log.d` / `Log.i` call sites are **not** migrated. They are verbosity-only
  and remain logcat-bound.

## 4. Architecture

New package `com.lastwave.app.diagnostics`, split so the logic is testable
without an Android runtime:

```
diagnostics/core/          pure JVM — no android.* import anywhere
  LogLevel.kt              enum VERBOSE..ERROR with priority + 1-char symbol
  LogRecord.kt             one captured event
  LogFormatter.kt          LogRecord -> fixed-column text
  LogStore.kt              append, rotate, prune
  LogQueue.kt              bounded queue + drop accounting
  LogTail.kt               bounded ring of recent lines (the crash lead-up)
  Retention.kt             retention constants + pruning rules

diagnostics/               thin Android shell over core
  AppLog.kt                public facade: v/d/i/w/e
  LogWriter.kt             background writer thread
  LogConfig.kt             verbosity resolution, debuggable flag, verbose pref
  CrashType.kt             closed enum of detected failure classes
  CrashRecorder.kt         one file per crash, prune to 10
  CrashHandler.kt          replaces CrashGuard: uncaught-exception handler
  AnrWatchdog.kt           main-looper ping watchdog
  ExitReasonReader.kt      ApplicationExitInfo (API 30+)
  SessionMarker.kt         running/clean lifecycle marker
  Diagnostics.kt           assembles the subsystem in dependency order
  DiagnosticsReport.kt     sectioned report builder (PR 2)
```

Every unit has one purpose. The `core` boundary is what lets the formatting,
rotation, retention and drop-accounting rules be exercised as plain JUnit — 43
tests currently cover them.

Every unit has one purpose. `LogStore`, `LogFormatter`, `LogQueue`,
`Retention`, and `DiagnosticsReport` have no Android dependencies beyond
`java.io` and are directly unit-testable on the JVM. `AppLog` and
`CrashRecorder` are thin Android-facing shells over them.

Single-process app (verified: no `android:process` attribute anywhere in
`AndroidManifest.xml`), so one writer thread serves the whole process.

### 4.1 Call flow

```
AppLog.w(TAG, msg, throwable)
  |
  +-- android.util.Log.w(TAG, msg, throwable)      // always; logcat + debugger unchanged
  |
  +-- if (level >= LogConfig.diskThreshold)
        LogQueue.offer(LogRecord)                  // non-blocking, bounded
              |
              v
        LogWriter thread                            // batches, never the caller
              |
              v
        LogStore.append -> format -> rotate if needed -> prune
```

`AppLog` is safe to call before `install()`: uninitialised state routes to
`android.util.Log` only. No file handle is touched, no exception is thrown.

### 4.2 Concurrency contract

- **Callers never block.** `LogQueue.offer` is a non-blocking `offer` on a
  `LinkedBlockingQueue` with capacity 4096.
- **On overflow, drop the newest** entry and increment a drop counter. The
  lead-up to a crash is the most valuable data in the log, so the oldest
  entries are the ones worth protecting. When the queue next drains below
  half capacity, the writer emits one `WARN` line reporting how many entries
  were dropped, so a storm is visible rather than silent.
- **Batching.** The writer collects up to 64 records or 200 ms, whichever comes
  first, writes them as one buffer, then flushes.
- **Flush points.** `MainActivity.onStop`, `MusicPlaybackService.onDestroy`, and
  the crash handler all call `LogWriter.flush()` synchronously. This is what
  makes "the log survives the app closing" true rather than aspirational.
- **Crash path bypasses the queue entirely** and writes synchronously — the
  process is already dying and cannot afford to wait on a thread pool.

## 5. File layout and formats

Root: `context.filesDir/diagnostics/`.

### 5.1 Crashes — `diagnostics/crashes/`

One file per crash. Filename sorts chronologically and is filesystem-safe:

```
crash-2026-10-02T14-31-05-123_pid-8123.txt
crash-2026-10-02T16-02-44-901_pid-8123.txt
```

`CrashType` is a closed enum; the first six are the possible values, the last
three are file-format artefacts that the viewer renders distinctly:

| Value | Source |
|---|---|
| `UNCAUGHT_EXCEPTION` | `UncaughtExceptionHandler` |
| `ANR` | `AnrWatchdog` |
| `NATIVE_CRASH` | `ApplicationExitInfo.REASON_CRASH_NATIVE` |
| `LOW_MEMORY` | `REASON_LOW_MEMORY` |
| `EXCESSIVE_RESOURCE_USE` | `REASON_EXCESSIVE_RESOURCE_USAGE` |
| `FREEZER` | `REASON_FREEZER` |
| `ABORT` | `REASON_CRASH` with an abort-style description |
| `UNKNOWN_TERMINATION` | unclean-exit marker, no `ApplicationExitInfo` record |
| `LEGACY` | one-time import of `lastwave_crash_guard.log` |

`UNKNOWN_TERMINATION` is never presented as a crash; the viewer labels it
"ended without a clean-shutdown marker".

Retained: **newest 10**. On recording the 11th, the oldest is deleted.

Format:

```
================================================================
 LastWave crash report
================================================================
Crash type   : UNCAUGHT_EXCEPTION
Recorded at  : 2026-10-02 14:31:05.123
App version  : 4.2.2 (22)
Process      : pid=8123  session started 14:20:11.004  uptime 10m54s
Thread       : main
Device       : Google Pixel 8  sdk=35  abi=arm64-v8a  release=16
Memory       : avail=412MB/8192MB  lowMemory=true
Storage      : free=1.8GB/128GB

--- message ---
java.lang.IllegalStateException: sink closed

--- stack trace ---
java.lang.IllegalStateException: sink closed
	at com.lastwave.app.playback.NativeProcessingAudioSink.write(NativeProcessingAudioSink.kt:214)
	...

--- log lead-up (last 40 entries) ---
2026-10-02 14:31:04.881  I  MusicPlayer        main:12  ...
2026-10-02 14:31:05.001  W  NativeAudioSink    audio:3  Underrun: 12 frames dropped
...

--- end ---
```

The **log lead-up** is the single most valuable addition: the 40 most recent
operational entries preceding the crash, read from an in-memory tail buffer so
it works even if the queue was mid-batch. Nothing in the current system
captures this.

`CrashType` is a closed enum, defined once in `CrashType.kt` and consumed by
`CrashRecorder`, the viewer, and `DiagnosticsReport`. Seven values, each with
exactly one producing mechanism:

| Value | Producing mechanism |
|---|---|
| `UNCAUGHT_EXCEPTION` | `Thread.setDefaultUncaughtExceptionHandler` |
| `ANR` | `AnrWatchdog` stall detection |
| `NATIVE_CRASH` | `ApplicationExitInfo.REASON_CRASH_NATIVE` (API 30+) |
| `LOW_MEMORY` | `REASON_LOW_MEMORY` (API 30+) or the unclean-exit marker |
| `EXCESSIVE_RESOURCE_USE` | `REASON_EXCESSIVE_RESOURCE_USAGE` (API 30+) |
| `UNKNOWN_TERMINATION` | unclean-exit marker with no matching `ApplicationExitInfo` |
| `LEGACY` | one-time import of `lastwave_crash_guard.log` |

`REASON_USER_REQUESTED` and `REASON_USER_STOPPED` are normal user actions and
produce no record. `UNKNOWN_TERMINATION` is never presented as a crash — the
viewer labels it "ended without a clean-shutdown marker", which may mean the
system killed the process or the user swiped the app from recents.

### 5.2 Operational log — `diagnostics/logs/`

```
log-2026-10-02T14-31-05-123.txt
```

Rotate at **256 KB**. Retain **5** files → hard ceiling of 1.25 MB (the active
file plus four rotated). Pruning is by filename sort, newest first.

Line format — fixed columns so the file both reads cleanly and greps:

```
2026-10-02 14:31:05.123  W  MusicPlayer           main  Route rebuild after device change
2026-10-02 14:31:06.004  E  NativeAudioSink       audio  Underrun: 41 frames dropped
    java.lang.IllegalStateException: sink closed
        at com.lastwave.app.playback.NativeProcessingAudioSink.write(NativeProcessingAudioSink.kt:214)
```

- Timestamp `yyyy-MM-dd HH:mm:ss.SSS`, level as a single char, tag padded to 20
  (truncated with `…` past that), thread name, two spaces, message.
- Source line numbers are deliberately **not** captured. Reading them means
  walking the call stack on every log call, which is not affordable on a real-time
  audio callback. The thread name carries the useful part.
- A throwable renders as indented continuation lines. One event may span multiple
  physical lines; a message's own newlines are replaced with `⏎` so a single-line
  message never breaks the column layout.
- File names carry a fixed-width monotonic sequence alongside the timestamp.
  A variable-width or suffix-based disambiguator would break the invariant that
  filename order equals creation order, and retention pruning would then delete
  the wrong file — a bug the LogStore tests caught during development.
- File header on creation records app version, device, and session start.

### 5.3 Startup trail

Retained: **10 launches** (up from 2), one block per launch, oldest first.

### 5.4 Legacy import

On first init, if `dataDir/lastwave_crash_guard.log` exists it is copied to
`diagnostics/crashes/crash-legacy-lastwave_crash_guard.txt` and the original
renamed to `.imported`. Existing reports stay readable; the original is never
silently deleted.

## 6. Detection matrix

| Failure class | Mechanism | Availability |
|---|---|---|
| Uncaught Java/Kotlin exception | `Thread.setDefaultUncaughtExceptionHandler` | all |
| ANR / main-thread stall | `AnrWatchdog` | all |
| Native crash (SIGSEGV/SIGABRT, incl. RenderThread) | `ExitReasonReader` `REASON_CRASH_NATIVE` | API 30+ |
| Low-memory kill | `REASON_LOW_MEMORY` + unclean-exit marker | API 30+ |
| Excessive resource use | `REASON_EXCESSIVE_RESOURCE_USAGE` | API 30+ |
| Unknown / system kill | unclean-exit marker | all |
| Coroutine failure in a scope | `CoroutineExceptionHandler` on the application and service scopes | all |
| Playback engine events | `PlaybackDiagnostics` — all 6 counters wired | all |
| Startup subsystem failure | `StartupTrail`, 10 launches | all |
| Service teardown | `onDestroy` / `onTaskRemoved` markers | all |

### 6.1 AnrWatchdog

A single daemon thread. Every 1 s it posts a token to the main `Looper` and
waits up to 5 s for it to run. If the token has not executed, the main thread
is considered stalled.

- Threshold **5 s**, matching Android's input-dispatch ANR limit. A short GC
  pause will not trip it.
- On trip: capture the main thread's full stack via `Looper.getMainLooper().thread.stackTrace`,
  write an `ANR` crash record, then **keep watching**. The watchdog does not
  kill the process — Android's own ANR machinery owns that decision.
- A second record is written if the stall is still ongoing at 30 s, capturing
  whether the stack changed, which distinguishes a slow task from a hard deadlock.
- Re-arm only after the main thread responds, so one stall produces one record,
  not one per second.
- False positives during legitimately heavy main-thread work (large playlist
  import) are possible; the record states the observed stall duration and stack
  so a reader can judge.

### 6.2 ExitReasonReader

On API 30+, `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 10)`
is queried at startup. Each `ApplicationExitInfo` with an abnormal `reason`
produces a crash record carrying `timestamp`, `reason`, `description`, `pss`,
and `rss`.

Deduplication: the highest processed `timestamp` is persisted in SharedPreferences
and compared on every launch, so a given exit is recorded exactly once no
matter how many times the app is restarted. On API 29 the reader is a no-op and
the unclean-exit marker is the only signal.

### 6.3 Unclean-exit marker

`SessionMarker` writes `running` with pid and session start time during
`attachBaseContext`. `MainActivity.onDestroy` (when `isFinishing`) and
`MusicPlaybackService.onDestroy` write `clean`. If the next launch finds a
marker still `running`, the previous session ended without a clean marker and a
`UNKNOWN_TERMINATION` record is written.

This is honest about its own limits: the record is labelled "no clean-shutdown
marker — the system may have killed the process, or the app may have been
swiped from recents." It is not claimed as a crash.

### 6.4 Level policy

| Level | logcat | disk |
|---|---|---|
| `ERROR` | yes | always |
| `WARN` | yes | always |
| `INFO` | yes | debuggable build, or verbose enabled |
| `DEBUG` | yes | debuggable build, or verbose enabled |
| `VERBOSE` | yes | verbose enabled only |

`VERBOSE` is never written to disk in a normal release build. A user who
enables **verbose logging** in the diagnostics screen gets `INFO`/`DEBUG`/
`VERBOSE` on disk, can reproduce a problem, and can then share the file. The
preference (`SharedPreferences` file `diagnostics`, key `verbose`) is read at
install and re-read by the writer when the viewer changes it.

## 7. User interface

New destination `Screen.Diagnostics` (`route = "diagnostics"`) following the
existing `HomeSectionsScreen` pattern exactly: `PredictiveBackScreen`,
`ExpressiveHeader`, `adaptiveContentWidth(860.dp)`,
`.safeHorizontalContentPadding()`, `LazyColumn` with
`bottom = 32.dp + safeDrawingBottomPadding()`, own `@HiltViewModel`. Grouped
rows use `ExpressiveGroup` / `ExpressiveGroupRow` from
`ui/common/ExpressiveGroup.kt` — the shared replacements for
`SettingsScreen.kt`'s private card helpers. Tab strip uses the existing
`ConnectedButtonGroup`.

Three tabs:

**Crashes** — up to 10 rows, newest first. Each row shows a type-coloured
icon, timestamp, one-line summary, and thread name. Tap opens the full record
(header, lead-up, complete stack) in a detail view with a back affordance.
Header action deletes all; per-row long-press deletes one. Empty state explains
that no crashes have been recorded.

**App log** — newest-last `LazyColumn` of parsed entries. Level filter chips
(All / Warn / Error), a tag dropdown populated from the loaded entries, and a
search field matching message, tag, and thread. Auto-scroll pauses when the
user scrolls up and offers a "jump to latest" affordance. Entries carrying a
stack trace expand inline on tap.

**Report** — the sectioned report with **Share** and **Copy** actions. The
existing About ▸ "Export diagnostics" card now navigates here instead of
sharing directly; `SettingsTab.ABOUT`'s `SettingsGroup` goes `rowCount` 4 → 5
and keeps the `about.diagnostics` id so existing `SettingsSearchIndex` entries
and highlight logic still resolve.

### 7.1 Report structure

`DiagnosticsReport` replaces the current ad-hoc builder and becomes a pure,
testable object. Section order:

1. **Header** — generation timestamp, app version/code, package, install source
2. **Device** — manufacturer, model, SDK, release, ABI, locale, screen, density
3. **Process** — pid, session start, uptime, Java heap, native heap, `lowMemory`
4. **Crash summary table** — all retained crashes: index, timestamp, type, thread, first line
5. **Crash details** — full text of every retained crash, each under a clear rule
6. **Playback diagnostics** — `PlaybackDiagnostics.snapshot()` plus all 6 counters
7. **Startup trail** — last 10 launches
8. **Operational log** — active file plus retained rotations, full text
9. **Log file manifest** — filename, size, modification time for every file written
10. **Signal path** — the existing `SignalPathReport` when available
11. **Widget snapshot** — existing `NowPlayingWidgetSnapshot`
12. **System logcat** — best-effort `logcat -d -v threadtime --pid=<self>`, 3000-line cap, 8 s timeout, clearly labelled as possibly unavailable and as covering only the current process

Sections that have no data say so explicitly (`(none recorded)`) rather than
being omitted, so a reader can tell "nothing happened" from "not collected".

The `logcat` shell-out is demoted from backbone to one labelled section. It
remains useful for native Oboe and USB-driver output that the Kotlin logger
cannot see, but it is no longer presented as the crash record.

## 8. Migration

1. **Facade call sites.** Convert all `Log.w` (150) and `Log.e` (86) call sites
   across 39 files to `AppLog.w` / `AppLog.e`. Messages and tags are unchanged.
   Some files use `import android.util.Log`, others call
   `android.util.Log.x` fully qualified — both forms are handled. The
   `import android.util.Log` line stays where `Log.d`/`Log.i` calls remain.
2. **Dead code.** Wire the 5 never-incremented `PlaybackDiagnostics` counters at
   their natural call sites (`streamOpens`, `streamRestarts`, `routeRebuilds`,
   `underrunFallbacks`, `gaplessReuses`) and make `snapshot()` reachable from the
   report.
3. **ViewModel slimming.** `SettingsViewModel` lines 772–869 —
   `exportDiagnostics`, `buildDiagnosticsReport`, `readOwnLogcat`,
   `readCrashGuardLog` — move to `DiagnosticsReport.kt`. The ViewModel keeps a
   thin `exportDiagnostics()` that delegates.
4. **Replacement.** `CrashGuard.kt` is deleted and replaced by
   `CrashRecorder.kt`. `StartupTrail.kt` is retained but its retention moves to
   `Retention.KEEP_LAUNCHES` and it gains an upgrade path for the 2-launch file.
5. **Coroutine handlers.** `MusicPlaybackService`'s handler is replaced with one
   that also records to disk; an application-scope handler is added.
6. **Navigation and strings.** `Screen.kt`, `NavGraph.kt`, `SettingsScreen.kt`
   (signature + About card + `getTargetSectionIndex` if a new `item {}` is
   added), `SettingsSearchIndex.kt`, and `res/values/strings.xml`.

## 9. Strings

~35 new keys are added to `app/src/main/res/values/strings.xml` only. The other
12 locale directories fall back to English automatically, matching the 60
already-untranslated strings at the tail of the default file. No
machine-translated copy is committed into locale files.

## 10. Testing

Pure-JVM unit tests (no Android runtime needed):

- `LogFormatterTest` — column alignment, level symbols, tag truncation at 20,
  newline collapsing to `⏎`, throwable rendering, blank-line handling
- `LogStoreTest` — append, rotation at 256 KB, prune to 5, filename sort order,
  file header, concurrent append safety
- `RetentionTest` — prune-to-10 crashes, prune-to-5 logs, boundary at exactly
  the limit
- `LogQueueTest` — offer/drain, drop-newest on overflow, drop-counter reporting
- `DiagnosticsReportTest` — every section present, `(none recorded)` for empty
  inputs, no crash record lost, log manifest sorted

Robolectric (already a test dependency) covers `CrashRecorder` file creation
and `SessionMarker` round-tripping.

### 10.1 Local verification, without Gradle

The constraint is no Gradle build, but "no local compiler" was not required.
The Kotlin compiler and JUnit jars already sit in the Gradle *cache*, so the
`diagnostics` package is compiled and its tests executed directly via
`kotlin-compiler-embeddable` plus `JUnitCore` — no Gradle, no APK, no Android
toolchain. `android.jar` from `platforms/android-37.0` is supplied on the
compilation classpath, so the Android-facing classes are type-checked too.

This is what makes the TDD cycle genuine for this subsystem: each test was
watched fail for the expected reason before its implementation was written. It
caught four real defects that a compile-only check would have missed or that
would otherwise have shipped:

- the tag-truncation ellipsis silently omitted (the column was padded, never
  truncated)
- `LogFormatter` collapsing newlines with mismatched `replace` overloads
- **log file naming that broke chronological ordering**, so retention pruning
  deleted the wrong files and could prune the active one
- a `Context` reference in `CrashHandler` with no import

What this does **not** cover: anything touching Hilt, Compose, Room, or the
manifest, and the `-PminSdk=24` legacy build. CI remains the gate for those.

### 10.2 CI verification

The user has directed that Gradle is not run locally. **CI is therefore the
compile gate.** `.github/workflows/build.yml` runs on every pull request and
executes:

1. `:app:testDebugUnitTest` (line 42) — runs the tests written above
2. `assembleRelease assembleRawRelease` (line 58) — R8 + resource shrinking,
   the build most likely to surface a reflection or string-resource mistake
3. `assembleRelease -PminSdk=24` (line 118) — the **API 24** legacy build,
   which is what catches a missing version guard

Consequences for how this work is delivered:

- CI `paths-ignore` includes `'**.md'` and `'**.yml'`, so a docs-only change
  runs no build. Any PR containing Kotlin changes does.
- Every PR must be read for the check result before the next is built. A green
  run is the only compile evidence available, so it is treated as mandatory
  rather than advisory.
- Nothing is claimed as verified locally. The final report states plainly that
  all verification came from CI, not from a local build.

## 11. Risks

| Risk | Mitigation |
|---|---|
| ~236 mechanical call-site edits with no compiler safety net | Migration is a single repetitive pattern; each edit preserves the message and tag verbatim. Highest-risk files (`MusicPlayer`, `NativeProcessingAudioSink`) are edited first and reviewed individually. |
| ANR watchdog false positives on heavy main-thread work | 5 s threshold above GC-pause range; record states observed duration and stack so a reader can judge; one record per stall, not per second. |
| Disk growth | Hard ceilings: 10 crash files, 5 × 256 KB logs. Pruning runs on every record. |
| Audio-thread impact | Non-blocking enqueue only; all I/O on the writer thread; queue drops are counted, never blocking. |
| Verbose logging leaking user data into a shared file | Verbose is off by default in release. The report labels which levels were captured. |
| Old `logcat` path removed breaks existing bug reports | Section retained and relabelled rather than deleted. |

## 12. Out of scope

Remote upload, native/C++ logging changes, `Log.d`/`Log.i` migration,
per-build-type log configuration, log upload to GitHub issue templates, and any
Gradle modification of any kind.

## 13. Delivery — 3 stacked PRs

All three target `main` so that CI runs on each; each branch contains the
previous one, so the incremental commits are the review surface. All branch
from `origin/main` (`00aa9e03`), not from the in-flight
`fix/crossfade-lossless-module` branch, whose commit is unrelated.

| PR | Branch | Contents |
|---|---|---|
| 1 | `feat/logging-core` | `LogLevel`, `LogRecord`, `LogFormatter`, `LogStore`, `LogQueue`, `LogWriter`, `Retention`, `LogConfig`, `CrashType`, `CrashRecorder`, `AnrWatchdog`, `ExitReasonReader`, `SessionMarker`, `AppLog`; wires into `LastWaveApplication` / `MainActivity` / `MusicPlaybackService`; deletes `CrashGuard`; unit tests |
| 2 | `feat/logging-viewer` | `DiagnosticsScreen` + `DiagnosticsViewModel`, `Screen.Diagnostics` route, `NavGraph` registration, About card rewiring, `SettingsSearchIndex` entry, strings, `DiagnosticsReport` |
| 3 | `feat/logging-migration` | all 236 `Log.w`/`Log.e` → `AppLog.w`/`AppLog.e` call sites; the 5 dead `PlaybackDiagnostics` counters |

PR 1 is the foundation and is the one that must be right — everything else
depends on its file format and retention rules. PR 3 is deliberately last so
that if the 236-site sweep hits a wall, the core and the viewer are already
merged and usable.

The spec document itself is included in PR 1 rather than shipped as a
docs-only change, since a docs-only PR would trigger no CI.
