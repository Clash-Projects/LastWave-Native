# Native C++ AutoDJ Automix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a native C++ Automatic DJ (Automix) engine inside LastWave's FFmpeg + C++ Oboe DSP pipeline, featuring Mixxx-style dual-deck state machine, silence trimming (< -45 dBFS), kick-band BPM & phase detection, WSOLA time-stretching, Linkwitz-Riley 160 Hz bass-swapping, and a 1.2s echo fade-out tail.

**Architecture:** A native C++ core (`AutoMixEngine`) coordinates dual-deck Float32 PCM streams from FFmpeg/Media3. It performs kick-band onset autocorrelation to sync beats, scales tempo via WSOLA during the mix, applies Linkwitz-Riley 4th order 160 Hz bass-swap at the midpoint downbeat, and diffuses the outgoing track with a 1.2s echo tail. Kotlin layers manage mutual exclusivity with standard crossfade, settings, and player "Fade Now" triggers.

**Tech Stack:** C++17, GCC/Clang, Oboe, Media3/ExoPlayer, Jellyfin FFmpeg Decoder, Android NDK, Kotlin Coroutines, Jetpack Compose.

**Spec:** [`docs/automix-engine-design.md`](file:///c:/Users/Ajisth/.gemini/antigravity-ide/scratch/LastWave-native/docs/automix-engine-design.md)

## Global Constraints

- Strictly DO NOT run Gradle build commands (`dont gradle build`).
- Verify native components via direct `g++ -std=c++17` test harnesses and Kotlin components via targeted unit tests / static verification.
- Automix and standard crossfade are mutually exclusive: enabling one disables the other.
- Zero heap allocations inside real-time audio render callbacks.
- Bit-Perfect output bypasses Automix.

## Review Focus

1. **Silence Trimming False Positives**: Songs with quiet ambient intros must not be chopped excessively; RMS windowing with $-45\text{ dBFS}$ floor prevents clipping valid audio.
2. **Tempo Difference Exceeding ±8%**: Tracks that cannot sync must transition cleanly using frequency-split bass swap without pitch artifacts.
3. **Midpoint Downbeat Alignment**: Bass-swap must switch on the downbeat without phase clicks or energy dips.
4. **Post-Mix Recovery Smoothing**: 5-second linear tempo recovery ramp must return to 1.0x without pitch jitter.
5. **Queue Edits Mid-Transition**: Editing the queue or clearing tracks during an active mix must safely reset the standby deck without audio dropouts.

---

### Task 1: Linkwitz-Riley 4th Order 160 Hz Crossover & Bass-Swap Filter

**Files:**
- Create: `app/src/main/cpp/LinkwitzRileyFilter.h`
- Test: `app/src/main/cpp/tests/test_linkwitz_riley.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace lastwave::audio {
  class LinkwitzRileyFilter {
  public:
      LinkwitzRileyFilter() noexcept;
      void configure(double sampleRate, double crossoverHz = 160.0) noexcept;
      void reset() noexcept;
      // Frequency-split processing: separates input stereo into low (<160Hz) and high (>=160Hz)
      void processSplit(const float* inStereo, float* outLowStereo, float* outHighStereo, std::size_t frames) noexcept;
      // In-place high-pass (bass cut)
      void processHighPass(float* stereo, std::size_t frames) noexcept;
      // In-place low-pass
      void processLowPass(float* stereo, std::size_t frames) noexcept;
  };
  }
  ```

- [ ] **Step 1: Write test harness `test_linkwitz_riley.cpp`**

```cpp
#include "../LinkwitzRileyFilter.h"
#include <cassert>
#include <cmath>
#include <vector>
#include <iostream>

int main() {
    using namespace lastwave::audio;
    LinkwitzRileyFilter filter;
    filter.configure(48000.0, 160.0);

    const std::size_t frames = 4800;
    std::vector<float> input(frames * 2, 0.0f);
    // 50 Hz sine (pure bass) and 1000 Hz sine (high)
    for (std::size_t i = 0; i < frames; ++i) {
        float lowSine = std::sin(2.0f * M_PI * 50.0f * i / 48000.0f);
        float highSine = std::sin(2.0f * M_PI * 1000.0f * i / 48000.0f);
        input[i * 2] = lowSine + highSine;
        input[i * 2 + 1] = lowSine + highSine;
    }

    std::vector<float> low(frames * 2, 0.0f);
    std::vector<float> high(frames * 2, 0.0f);
    filter.processSplit(input.data(), low.data(), high.data(), frames);

    // Sum of low + high must reconstruct input with flat magnitude
    for (std::size_t i = 500; i < frames - 500; ++i) {
        float reconstructed = low[i * 2] + high[i * 2];
        float original = input[i * 2];
        assert(std::abs(reconstructed - original) < 0.05f);
    }

    std::cout << "LinkwitzRileyFilter tests passed!\n";
    return 0;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `g++ -std=c++17 -o test_lr app/src/main/cpp/tests/test_linkwitz_riley.cpp && ./test_lr`
Expected: Compilation failure (missing `LinkwitzRileyFilter.h`).

- [ ] **Step 3: Implement `LinkwitzRileyFilter.h`**

Implement cascaded 2nd-order Butterworth biquad filters ($Q = \frac{1}{\sqrt{2}} \approx 0.7071$) yielding 4th-order Linkwitz-Riley ($Q = 0.5$ critical damping squared, $-6\text{ dB}$ at crossover frequency, flat summed amplitude).

- [ ] **Step 4: Run test to verify it passes**

Run: `g++ -std=c++17 -o test_lr app/src/main/cpp/tests/test_linkwitz_riley.cpp && ./test_lr`
Expected: Output `LinkwitzRileyFilter tests passed!`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/LinkwitzRileyFilter.h app/src/main/cpp/tests/test_linkwitz_riley.cpp
git commit -m "feat(automix): implement 4th-order Linkwitz-Riley 160 Hz crossover filter"
```

---

### Task 2: 60–140 Hz Kick Band BPM & Phase Detector

**Files:**
- Create: `app/src/main/cpp/BpmPhaseDetector.h`
- Create: `app/src/main/cpp/BpmPhaseDetector.cpp`
- Test: `app/src/main/cpp/tests/test_bpm_detector.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace lastwave::audio {
  struct BeatDetectionResult {
      float bpm{120.0f};
      float confidence{0.0f};
      std::uint64_t lastDownbeatFrame{0};
  };

  class BpmPhaseDetector {
  public:
      BpmPhaseDetector() noexcept;
      void configure(double sampleRate) noexcept;
      void reset() noexcept;
      void process(const float* stereo, std::size_t frames) noexcept;
      [[nodiscard]] BeatDetectionResult getResult() const noexcept;
  };
  }
  ```

- [ ] **Step 1: Write test harness `test_bpm_detector.cpp`**

Simulate a 128 BPM kick drum (impulse every $\frac{48000 \times 60}{128} = 22500$ samples) and verify detected BPM is within $128 \pm 1.5$ BPM.

- [ ] **Step 2: Run test to verify it fails**

Run: `g++ -std=c++17 -o test_bpm app/src/main/cpp/tests/test_bpm_detector.cpp app/src/main/cpp/BpmPhaseDetector.cpp && ./test_bpm`
Expected: Compilation failure.

- [ ] **Step 3: Implement `BpmPhaseDetector.h` and `BpmPhaseDetector.cpp`**

Implement 2nd-order Butterworth bandpass filter centered at 100 Hz ($Q = 1.0$), energy envelope extraction, and autocorrelation over lag window 60–180 BPM.

- [ ] **Step 4: Run test to verify it passes**

Run: `g++ -std=c++17 -o test_bpm app/src/main/cpp/tests/test_bpm_detector.cpp app/src/main/cpp/BpmPhaseDetector.cpp && ./test_bpm`
Expected: Output `BpmPhaseDetector tests passed!`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/BpmPhaseDetector.h app/src/main/cpp/BpmPhaseDetector.cpp app/src/main/cpp/tests/test_bpm_detector.cpp
git commit -m "feat(automix): implement kick-band BPM and beat phase detector"
```

---

### Task 3: C++ WSOLA Pitch-Preserving Time-Stretcher & Recovery Ramp

**Files:**
- Create: `app/src/main/cpp/WsolaTimeStretcher.h`
- Create: `app/src/main/cpp/WsolaTimeStretcher.cpp`
- Test: `app/src/main/cpp/tests/test_wsola.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace lastwave::audio {
  class WsolaTimeStretcher {
  public:
      WsolaTimeStretcher() noexcept;
      void configure(double sampleRate, std::size_t channels = 2) noexcept;
      void reset() noexcept;
      void setTimeRatio(float ratio) noexcept; // e.g. 0.92 to 1.08
      void startRecoveryRamp(float targetRatio = 1.0f, float rampDurationSeconds = 5.0f) noexcept;
      std::size_t process(const float* input, std::size_t inputFrames, float* output, std::size_t outputCapacity) noexcept;
      std::size_t flush(float* output, std::size_t outputCapacity) noexcept;
      [[nodiscard]] float currentRatio() const noexcept;
  };
  }
  ```

- [ ] **Step 1: Write test harness `test_wsola.cpp`**

Verify that a test sine wave stretched by ratio $1.05\times$ preserves frequency/pitch, and verify that the 5-second recovery ramp smoothly settles `currentRatio()` back to `1.0f`.

- [ ] **Step 2: Run test to verify it fails**

Run: `g++ -std=c++17 -o test_wsola app/src/main/cpp/tests/test_wsola.cpp app/src/main/cpp/WsolaTimeStretcher.cpp && ./test_wsola`
Expected: Compilation failure.

- [ ] **Step 3: Implement `WsolaTimeStretcher.h` and `WsolaTimeStretcher.cpp`**

Implement overlap-add cross-correlation search on Float32 stereo samples with Hann window interpolation. Implement per-frame ratio interpolation during the 5.0-second recovery ramp.

- [ ] **Step 4: Run test to verify it passes**

Run: `g++ -std=c++17 -o test_wsola app/src/main/cpp/tests/test_wsola.cpp app/src/main/cpp/WsolaTimeStretcher.cpp && ./test_wsola`
Expected: Output `WsolaTimeStretcher tests passed!`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/WsolaTimeStretcher.h app/src/main/cpp/WsolaTimeStretcher.cpp app/src/main/cpp/tests/test_wsola.cpp
git commit -m "feat(automix): implement stereo WSOLA time-stretcher and tempo recovery ramp"
```

---

### Task 4: 1.2s Echo Fade-Out Tail Processor

**Files:**
- Create: `app/src/main/cpp/EchoTailProcessor.h`
- Test: `app/src/main/cpp/tests/test_echo_tail.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace lastwave::audio {
  class EchoTailProcessor {
  public:
      EchoTailProcessor() noexcept;
      void configure(double sampleRate, float bpm = 120.0f) noexcept;
      void reset() noexcept;
      void setEnabled(bool enabled) noexcept;
      // Injects dry input into delay line and adds attenuated decaying echo tail
      void process(float* stereo, std::size_t frames, float sendGain, float dryGain) noexcept;
  };
  }
  ```

- [ ] **Step 1: Write test harness `test_echo_tail.cpp`**

Feed an impulse with `sendGain = 1.0f`, verify decay across $1.2\text{ seconds}$ adheres to $T_{60}$ exponential decay curve with 4.5 kHz damping.

- [ ] **Step 2: Run test to verify it fails**

Run: `g++ -std=c++17 -o test_echo app/src/main/cpp/tests/test_echo_tail.cpp && ./test_echo`
Expected: Compilation failure.

- [ ] **Step 3: Implement `EchoTailProcessor.h`**

Implement circular ring delay buffer with single-pole low-pass damping filter and decay calculation for $T_{60} = 1.2\text{s}$.

- [ ] **Step 4: Run test to verify it passes**

Run: `g++ -std=c++17 -o test_echo app/src/main/cpp/tests/test_echo_tail.cpp && ./test_echo`
Expected: Output `EchoTailProcessor tests passed!`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/EchoTailProcessor.h app/src/main/cpp/tests/test_echo_tail.cpp
git commit -m "feat(automix): implement 1.2s tempo-synced echo fade-out tail processor"
```

---

### Task 5: Master C++ AutoMixEngine Dual-Deck State Machine & Silence Trimmer

**Files:**
- Create: `app/src/main/cpp/AutoMixEngine.h`
- Create: `app/src/main/cpp/AutoMixEngine.cpp`
- Test: `app/src/main/cpp/tests/test_automix_engine.cpp`

**Interfaces:**
- Produces:
  ```cpp
  namespace lastwave::audio {
  enum class AutoMixState : int {
      Idle = 0,
      PrebufferingDeckB = 1,
      Analyzing = 2,
      MonitoringOutro = 3,
      FadingAuto = 4,
      FadingFast = 5,
      PostRestore = 6
  };

  class AutoMixEngine {
  public:
      AutoMixEngine() noexcept;
      void configure(double sampleRate) noexcept;
      void setEnabled(bool enabled) noexcept;
      void setBarMode(int bars) noexcept; // 8 or 16
      void triggerFadeNow() noexcept;     // 4 bars
      void reset() noexcept;
      std::size_t writeDeckBPcm(const float* pcm, std::size_t frames) noexcept;
      void process(float* outStereoDeckA, std::size_t frames) noexcept;
      [[nodiscard]] AutoMixState getState() const noexcept;
      [[nodiscard]] float getTransitionProgress() const noexcept;
  };
  }
  ```

- [ ] **Step 1: Write test harness `test_automix_engine.cpp`**

Test leading silence trimming (< -45 dBFS), state transitions through `MonitoringOutro` -> `FadingAuto` -> `PostRestore`, 160 Hz bass-swap at 50% midpoint, and 4-bar `Fade Now` trigger.

- [ ] **Step 2: Run test to verify it fails**

Run: `g++ -std=c++17 -o test_engine app/src/main/cpp/tests/test_automix_engine.cpp app/src/main/cpp/AutoMixEngine.cpp app/src/main/cpp/WsolaTimeStretcher.cpp app/src/main/cpp/BpmPhaseDetector.cpp && ./test_engine`
Expected: Compilation failure.

- [ ] **Step 3: Implement `AutoMixEngine.h` and `AutoMixEngine.cpp`**

Assemble `LinkwitzRileyFilter`, `WsolaTimeStretcher`, `BpmPhaseDetector`, and `EchoTailProcessor` into the dual-deck state machine.

- [ ] **Step 4: Run test to verify it passes**

Run: `g++ -std=c++17 -o test_engine app/src/main/cpp/tests/test_automix_engine.cpp app/src/main/cpp/AutoMixEngine.cpp app/src/main/cpp/WsolaTimeStretcher.cpp app/src/main/cpp/BpmPhaseDetector.cpp && ./test_engine`
Expected: Output `AutoMixEngine tests passed!`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/AutoMixEngine.h app/src/main/cpp/AutoMixEngine.cpp app/src/main/cpp/tests/test_automix_engine.cpp
git commit -m "feat(automix): implement master dual-deck AutoMixEngine with silence trimming"
```

---

### Task 6: JNI Bridge & CMakeLists.txt Integration

**Files:**
- Create: `app/src/main/cpp/AutoMixBridge.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt:44-56`
- Create: `app/src/main/java/com/lastwave/app/playback/NativeAutoMixEngine.kt`

**Interfaces:**
- Produces:
  ```kotlin
  package com.lastwave.app.playback

  class NativeAutoMixEngine : java.io.Closeable {
      fun configure(sampleRate: Int)
      fun setEnabled(enabled: Boolean)
      fun setBarMode(bars: Int)
      fun triggerFadeNow()
      fun writeDeckBPcm(buffer: ByteBuffer, frameCount: Int): Int
      fun process(deckABuffer: ByteBuffer, frameCount: Int)
      val state: Int
      val progress: Float
  }
  ```

- [ ] **Step 1: Write `AutoMixBridge.cpp`**

Export JNI functions:
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeCreate`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeDestroy`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetEnabled`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetBarMode`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeTriggerFadeNow`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeWriteDeckBPcm`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeProcess`
`Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetState`

- [ ] **Step 2: Update `CMakeLists.txt`**

Add `AutoMixEngine.cpp`, `AutoMixBridge.cpp`, `WsolaTimeStretcher.cpp`, `BpmPhaseDetector.cpp` to `lastwave_audio`.

- [ ] **Step 3: Create `NativeAutoMixEngine.kt`**

Wrap JNI methods in a safe Kotlin class with handle checks.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/cpp/AutoMixBridge.cpp app/src/main/cpp/CMakeLists.txt app/src/main/java/com/lastwave/app/playback/NativeAutoMixEngine.kt
git commit -m "feat(automix): add JNI bridge and CMakeLists configuration"
```

---

### Task 7: SettingsPreferences & ViewModel (Mutual Exclusivity)

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/local/SettingsPreferences.kt`
- Modify: `app/src/main/java/com/lastwave/app/ui/settings/SettingsViewModel.kt`

**Interfaces:**
- Produces:
  - `SettingsPreferences.automixEnabled`: Boolean (default `false`)
  - `SettingsPreferences.automixBars`: Int (default `8`)
  - `setAutomixEnabled(enabled: Boolean)`: when true, sets `crossfadeEnabled = false`
  - `setCrossfadeEnabled(enabled: Boolean)`: when true, sets `automixEnabled = false`
  - `setAutomixBars(bars: Int)`: sets 8 or 16

- [ ] **Step 1: Update `SettingsPreferences.kt`**

Add `AUTOMIX_ENABLED` and `AUTOMIX_BARS` Datastore keys. Ensure mutual exclusivity in `setAutomixEnabled` and `setCrossfadeEnabled`.

- [ ] **Step 2: Update `SettingsViewModel.kt`**

Expose `setAutomixEnabled(enabled: Boolean)` and `setAutomixBars(bars: Int)`.

- [ ] **Step 3: Verify mutual exclusivity logic with a Kotlin test**

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/local/SettingsPreferences.kt app/src/main/java/com/lastwave/app/ui/settings/SettingsViewModel.kt
git commit -m "feat(automix): add Automix preferences with crossfade mutual exclusivity"
```

---

### Task 8: AutoMixSchedule & MusicPlayer Playback Integration

**Files:**
- Create: `app/src/main/java/com/lastwave/app/playback/AutoMixSchedule.kt`
- Modify: `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt`

**Interfaces:**
- Produces:
  ```kotlin
  fun planAutoMix(input: AutoMixPlanInput): AutoMixDecision
  ```
  - Intercepts player `next()` command: if Automix is active and playing, triggers `triggerFadeNow()` instead of a hard track cut.
  - Pre-buffers next track on Deck B using the existing secondary ExoPlayer instance.

- [ ] **Step 1: Create `AutoMixSchedule.kt`**

Pure scheduling state machine function `planAutoMix` calculating bar durations from BPM, arm leads, and hand-off triggers.

- [ ] **Step 2: Update `MusicPlayer.kt`**

Wire `NativeAutoMixEngine` into `MusicPlayer`. Update `next()` to invoke `triggerFadeNow()` when Automix is enabled. Feed Deck B PCM frames into native Automix engine during overlap.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lastwave/app/playback/AutoMixSchedule.kt app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt
git commit -m "feat(automix): wire Automix dual-deck scheduling and Fade Now into MusicPlayer"
```

---

### Task 9: UI Controls in SettingsScreen and PlayerHost

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt`

**Interfaces:**
- Produces:
  - Automix toggle row in SettingsScreen under Playback section.
  - Bar Length selector (8 Bars / 16 Bars) visible when Automix is ON.
  - Standard Crossfade row dims with informational message when Automix is ON.
  - "AutoDJ" active badge in Now Playing screen.

- [ ] **Step 1: Modify `SettingsScreen.kt`**

Add `AutomixRow` with toggle and `AutomixBarsRow` (8 vs 16 bars). Update `CrossfadeRow` to indicate that Automix supersedes crossfade when active.

- [ ] **Step 2: Modify `PlayerHost.kt`**

Add Automix active badge indicator in Now Playing / expanded player view.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lastwave/app/ui/settings/SettingsScreen.kt app/src/main/java/com/lastwave/app/ui/player/PlayerHost.kt
git commit -m "feat(automix): add Automix settings controls and player UI indicator"
```
