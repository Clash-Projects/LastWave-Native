# Advanced Auto DJ Engine Design Spec

## Overview
This document specifies the architecture and implementation of the **Advanced Auto DJ Engine** for LastWave-Native.
Using the open-source **Mixxx AutoDJ** subsystem (`src/library/autodj/autodjprocessor.cpp`) as the architectural foundation, this engine provides autonomous queue management, predictive background track prefetching, and seamless playback continuity, even when resolving YouTube Music streams takes several seconds.

## Scope & Boundaries
- **Scope Restriction:** Implement Auto DJ only.
- **Automix DSP Preservation:** Automix and its existing transition logic (Linkwitz-Riley 160 Hz bass-swap crossover, WSOLA stereo time-stretching, 1.2s echo tail, and 4-bar / 8-bar / 16-bar fades) are already implemented in native C++ and integrated. Auto DJ decides **what and when** to play; Automix and the playback pipeline decide **how** it plays and transitions.
- **No Gradle Builds:** All native code is verified using targeted standalone C++ test harnesses compiled with `g++ -std=c++17`.

---

## 1. Native C++ Core: `AutoDJProcessor`

Located at: `app/src/main/cpp/AutoDJProcessor.h` and `AutoDJProcessor.cpp`.

### 1.1 Lifecycle States
Each upcoming track passes through an atomic lifecycle:
```
[QUEUED] ---> [RESOLVING] ---> [RESOLVED] ---> [PREPARING] ---> [READY] ---> [PLAYING] ---> [FINISHED]
      \             |               \              |
       \            v                \             v
        +-----> [FAILED]              +-----> [CANCELLED]
                    |
              (retry/skip)
```
- `QUEUED (0)`: In queue, scheduled for prefetch.
- `RESOLVING (1)`: Background HTTP/InnerTube network extraction in flight.
- `RESOLVED (2)`: Valid stream URL, signature expiry, and audio format obtained.
- `PREPARING (3)`: Decoder/standby ExoPlayer buffering audio chunks.
- `READY (4)`: Standby player buffered and armed on Deck B; instantaneous transition ready.
- `PLAYING (5)`: Currently audible track on Deck A.
- `FINISHED (6)`: Completed playback.
- `WAITING_LATE (7)`: Outgoing track reached end while incoming track is still resolving; entered smooth buffering hold.
- `FAILED (8)`: Resolution or decoding failed; retry counter evaluated.
- `CANCELLED (9)`: Invalidated by user seek, track selection, or queue clearance.

### 1.2 Predictive Latency Estimator & Dynamic Lead Window
YouTube stream extraction latency is non-deterministic (typically 1.2s to 4.5s). The native processor tracks resolution latency via an Exponential Moving Average (EMA) and variance filter:
$$\mu_{k} = \alpha L_k + (1 - \alpha) \mu_{k-1}$$
$$\sigma^2_k = \alpha (L_k - \mu_k)^2 + (1 - \alpha) \sigma^2_{k-1}$$
where $\alpha = 0.2$, initial prior $\mu_0 = 2500\text{ ms}$, $\sigma_0 = 1000\text{ ms}$.

The dynamic prefetch lead window $T_{\text{lead}}$ is calculated as:
$$T_{\text{lead}} = \text{clamp}\Big(T_{\text{transition}} + \mu + 2.5\sigma + T_{\text{buffer\_margin}}, \; 15000\text{ ms}, \; 60000\text{ ms}\Big)$$
This ensures that resolution begins sufficiently early so that even a 99th-percentile slow resolution completes and buffers into Deck B before the outro transition window opens.

### 1.3 Decision Engine
The evaluator inspects:
- Outgoing track position and duration ($T_{\text{rem}} = \text{duration} - \text{position}$).
- Next track lifecycle stage.
- Unplayed queue depth.

Decisions:
- `DO_NOTHING (0)`: Current track playing, next track state is on schedule.
- `PREFETCH_NEXT (1)`: $T_{\text{rem}} \le T_{\text{lead}}$ and next track is `QUEUED`.
- `PREPARE_STANDBY (2)`: Next track is `RESOLVED` and standby player needs arming.
- `TRIGGER_TRANSITION (3)`: Outgoing track reached transition window and Deck B is `READY`.
- `WAIT_FOR_LATE_TRACK (4)`: Outgoing track ended but next track is still `RESOLVING` or `PREPARING`.
- `SKIP_FAILED_TRACK (5)`: Next track permanently failed; advance queue past it.
- `REFILL_QUEUE (6)`: Remaining unplayed tracks in queue $\le 2$; trigger endless queue extension.

---

## 2. JNI Interface & CMake Integration

- `app/src/main/cpp/AutoDJBridge.cpp`:
  - `Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeCreate`
  - `Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeDestroy`
  - `Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeRecordLatency`
  - `Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeEvaluate`
  - `Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeGetLatencyStats`
- Added to `app/src/main/cpp/CMakeLists.txt` alongside `AutoMixEngine`.

---

## 3. Kotlin Auto DJ Controller: `AutoDJController`

Located at: `app/src/main/java/com/lastwave/app/playback/autodj/AutoDJController.kt`.

### 3.1 Responsibilities
1. **Asynchronous Prefetch Pipeline:**
   - Coordinates background coroutine jobs for next track ($N+1$) and warm pre-resolve for track ($N+2$).
   - Feeds resolved streams directly into `MusicPlayer.registerPreparedStream()`.
   - Pre-caches audio ranges into `CacheDataSource` ahead of playback.
2. **URL Expiration & Freshness Guard:**
   - Checks YouTube signed stream expiration timestamps (`expire=` query parameter).
   - If stream expires in $< 60$ seconds, invalidates cache and triggers background re-resolution.
3. **Bounded Failure Recovery:**
   - Tracks retry attempts per track (max 3 retries with exponential backoff: 500ms, 1000ms, 2000ms).
   - If all retries fail, marks track unavailable in `unavailableMediaIds` and skips to the next queue item without infinite loops.
4. **Queue Replenishment Hook:**
   - When remaining unplayed items $\le 2$, triggers `extendRadioQueueIfNeeded` or `extendDiscoverQueueIfNeeded`.
5. **State Synchronization & Cancellation:**
   - Uses monotonic request generation tokens (`playRequestGeneration`).
   - Cancels stale prefetch jobs on manual `seekTo`, `next()`, `previous()`, or queue clearing.

---

## 4. Playback Integration (`MusicPlayer.kt`)

1. **Periodic Playback Loop:**
   - On each progress tick, queries `autoDJController.onPlaybackTick(pos, dur, isPlaying)`.
   - Handles C++ decisions (`PREFETCH_NEXT`, `PREPARE_STANDBY`, `TRIGGER_TRANSITION`, `REFILL_QUEUE`).
2. **Late Track Handling (`WAIT_FOR_LATE_TRACK`):**
   - In `handleNaturalTrackEnd()`: If outgoing track finishes while next track is still `RESOLVING`, do not abruptly silence or crash. Set `isBuffering = true` and wait for `AutoDJController` to complete preparation and transition cleanly.
3. **User Action Preservation:**
   - Manual `next()` and `previous()` remain immediately authoritative.

---

## 5. Settings & UI

1. **Preferences:**
   - `SettingsPreferences`: `AUTODJ_ENABLED` (boolean, default true).
   - `SettingsViewModel`: `setAutoDJEnabled(enabled)`.
2. **UI Controls:**
   - `SettingsScreen`: Auto DJ toggle under Audio & Playback settings.
   - `PlayerHost`: Status indicator in now-playing chrome (`Auto DJ · Resolving...`, `Auto DJ · Ready`).
