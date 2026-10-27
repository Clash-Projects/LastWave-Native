# Advanced Auto DJ Engine Implementation Plan

## Tasks

- [x] **Task 1: C++ Native `AutoDJProcessor` Core & Standalone Unit Tests**
  - Create `app/src/main/cpp/AutoDJProcessor.h` and `app/src/main/cpp/AutoDJProcessor.cpp`.
  - Implement lifecycle state machine (`QUEUED` to `FINISHED`, `WAITING_LATE`, `FAILED`, `CANCELLED`).
  - Implement statistical latency estimator (EMA mean and variance) and dynamic lead window calculation.
  - Implement `evaluate(...)` returning `AutoDJDecision`.
  - Verify with standalone C++ test `test_autodj_processor.cpp` compiled via `g++ -std=c++17 -O2`.

- [x] **Task 2: JNI Bridge & CMakeLists.txt Integration**
  - Create `app/src/main/cpp/AutoDJBridge.cpp`.
  - Update `app/src/main/cpp/CMakeLists.txt` to include `AutoDJProcessor.cpp` and `AutoDJBridge.cpp`.
  - Create Kotlin JNI wrapper `app/src/main/java/com/lastwave/app/playback/NativeAutoDJEngine.kt`.

- [x] **Task 3: Kotlin Auto DJ Controller (`AutoDJController.kt`)**
  - Create `app/src/main/java/com/lastwave/app/playback/autodj/AutoDJController.kt`.
  - Implement asynchronous prefetch pipeline for $N+1$ and $N+2$.
  - Implement latency recording into native C++ engine.
  - Implement URL expiration guard and bounded retry policy (max 3 retries).
  - Implement automatic queue refill trigger when unplayed queue $\le 2$.

- [x] **Task 4: Playback Pipeline Integration (`MusicPlayer.kt`)**
  - Inject and initialize `AutoDJController` in `MusicPlayer.kt`.
  - Wire periodic tick in `updateCrossfade` / `updateSignalPath`.
  - Update `handleNaturalTrackEnd()` to enter smooth buffering wait if next track is still resolving.
  - Add Auto DJ state fields to `MusicPlayerState`.

- [x] **Task 5: Settings Preferences & UI Integration**
  - Add `AUTODJ_ENABLED` in `SettingsPreferences.kt`.
  - Expose `setAutomixEnabled` / `setAutoDJEnabled` in `SettingsViewModel.kt`.
  - Add Auto DJ toggle in `SettingsScreen.kt`.
  - Update status badge in `PlayerHost.kt`.

- [x] **Task 6: Verification & Cleanup**
  - Run native C++ test suite via `g++` to verify all state transitions, latency estimators, and decisions.
  - Verify git diff for clean code adherence without extraneous debug code.
  - Confirm NO Gradle build was executed.
