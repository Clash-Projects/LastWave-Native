# AGENTS.md — LastWave (Android)

Rules for every agent working in this repo. Read this before touching code.
For any UI or visual work, also read [DESIGN.md](DESIGN.md).

## 1. First, classify the prompt

Decide what kind of request it is and answer in that shape. Do not mix them.

| Prompt type | What you deliver |
|---|---|
| **Bug / crash / log** ("X fails", a diagnostics file, a stack trace) | Root cause from evidence, then the smallest correct fix. Never fix a guess as if it were proven. |
| **Feature** ("add X", "make it like Y") | The behaviour asked for, in the existing patterns, nothing extra. |
| **Refactor / cleanup** ("remove spaghetti") | Same behaviour, less code. Behaviour changes are bugs here. |
| **Design / UI** | Follow [DESIGN.md](DESIGN.md). |
| **Question** ("why", "is it like X") | An answer. No code changes unless asked. |
| **Compare with another app** (SimpMusic, Metrolist, InnerTubeX) | Read their real source first, then cite what you read. Never answer from memory. |

If the user says "don't build", do not run Gradle. Verify by reading the code.

## 2. How to change code

- **Fewest lines that fully fix it.** Aim for a net-negative diff. A fix that adds
  a second code path next to the broken one is not a fix.
- **Rewrite, don't patch.** When a function is wrong or tangled, write it fresh
  and correct instead of stacking `if`s on top. Read the whole function first,
  then replace it in one piece.
- **Delete bad code when you touch it:** dead branches, unused params/imports,
  duplicated blocks, copy-pasted handlers, settings nobody needs, stale comments.
  If two call sites do the same thing, make one helper and call it twice.
- **No behaviour change by accident.** In a cleanup, every path must do exactly what
  it did before (same retries, same logs, same order).
- **Comment the *why*, never the *what*.** A good comment names the failure it
  prevents ("blind upsert wiped likes made meanwhile"). Delete comments that no
  longer match the code.
- **Fix the class of bug, not the instance.** If one call site was wrong, grep
  for every sibling with the same shape and fix them together.
- **No speculative features, flags or toggles.** If a setting only exists to turn
  a bug off, remove the setting and fix the bug.

## 3. Verify before you claim

- Read the code path end to end (caller → callee → storage) before changing it.
- After an edit, re-read the changed region. Check imports, types, call sites,
  and that every caller of a changed signature was updated (`grep` for it).
- State plainly what was **not** verified (not built, not run on a device, not
  proven from a log).

## 4. Repo facts that bite

- **Line endings are mixed (CRLF and LF).** Preserve each file's existing
  endings. Scripts that read and rewrite files must not convert them — a
  one-line edit must not show up as a whole-file diff. Check with `git diff --stat`.
- **minSdk 29**, plus a legacy build at `-PminSdk=24` (Android 7–9). Every API above
  24 needs a `Build.VERSION.SDK_INT` guard or an AndroidX compat call.
  `Notification.Builder.setColorized` (26) has crashed Android 7 before.
- **vivo build** (`-PvivoOrigin=true`) ships as package `com.tencent.qqmusic`. Do not
  assume `context.packageName == "com.lastwave.app"`.
- **Native libs must be 16 KB-aligned.** `libffmpegJNI.so` (media3-ffmpeg-decoder
  1.2.1+1) is not, so FFmpeg is gated by `FfmpegCompat.isUsable`.
- **InnerTubeX is called by reflection** (`InnerTubeXStreamExtractor`) because its
  Kotlin metadata is newer than the app's. Implement its interfaces with
  `java.lang.reflect.Proxy`, keep argument masks in sync with the library version.
- **YouTube stream rules** (from InnerTubeX's client catalog):
  - Fetch googlevideo in bounded ranges of ≤ 512 KB (`ChunkedRangeDataSource`,
    downloads use `YOUTUBE_RANGE_BYTES`). Open-ended ranges get a 403 mid-song.
  - Send the minting client's User-Agent with its URL.
  - IOS-family audio URLs need an iOS GVS token we can't mint — never use them for audio.
  - Age-restricted songs only resolve on signed-in WEB_REMIX/TVHTML5 with ciphers
    deciphered via the faraday player-config registry
    (`raw.githubusercontent.com/MetrolistGroup/faraday/...`; other URLs are rejected).
- **InnerTube 400s:** never mutate the shared `webConfig` on a request failure;
  retry the single request with a clean context. `hl`/`gl` must be normalized ASCII
  (Android returns legacy codes like `in`, `iw`).
- **Coroutines:** never swallow a `CancellationException` that isn't your own job's
  (`currentCoroutineContext().ensureActive()` before deciding). A blind
  `catch (e: CancellationException) { throw e }` around a shared request kills
  retries silently.
- **Shared state writes are compare-and-swap.** Playlist tracks use
  `replaceTracksForSync(..., expectedTracks)` / `updateTracksIfUnchanged`; never
  `upsert` a snapshot you read seconds ago.
- **ViewModel scope dies with the screen.** Work that must finish after the
  screen pops (delete, save) runs under `NonCancellable` or the app scope.
- **`MutableSharedFlow()` without a buffer drops `tryEmit`.** Use `emit` from a
  coroutine.
- Startup collectors that never complete (`flow.collect {}`) block everything after
  them in the same coroutine. Launch them separately.

## 5. Diagnostics

User reports come as `lastwave-diagnostics-*.txt`. Extract with `strings`.
Native-crash tombstones are decoded by `diagnostics/TombstoneSummary.kt`;
`LastWaveTraffic`/`LastWaveStream` lines show every network call and its status.
Find the evidence in the log before writing a fix, and quote it in the summary.

## 6. Reporting back

Lead with what changed and why, tied to the evidence. Then what is still
unverified or out of scope. No filler, no restating the request.
