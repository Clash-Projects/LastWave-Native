# Native C++ AutoDJ Automix Specification for LastWave

**Date:** 2026-10-25  
**Status:** Approved  
**Architectural Reference:** Open-Source Mixxx AutoDJ (`mixxxdj/mixxx`)  
- `src/library/autodj/autodjprocessor.cpp`
- `src/engine/bufferscalers/enginebufferscalest.h`
- `src/engine/filters/` (Linkwitz-Riley crossover & frequency splitting)

---

## 1. Executive Summary

This document specifies the design for a native C++ Automatic DJ (Automix) engine embedded inside LastWave's FFmpeg + C++ Oboe DSP pipeline. Automix delivers seamless, beat-matched, frequency-split track transitions inspired by Mixxx AutoDJ. It replaces static timer-based crossfading with dynamic musical-bar transitions (8 or 16 bars), kick-band BPM & phase alignment via WSOLA time-stretching, a Linkwitz-Riley 160 Hz bass-swap on the midpoint downbeat, and a 1.2-second echo fade-out tail on the outgoing track.

Automix and standard crossfade are strictly mutually exclusive: enabling Automix automatically disables standard crossfade in settings and playback logic.

---

## 2. Architecture & Dual-Deck State Machine

### 2.1 State Transition Model

Automix operates as a real-time dual-deck state machine coordinating Deck A (currently playing track) and Deck B (incoming queued track):

```
                       ┌─────────────────────────┐
                       │     STATE_IDLE          │ (Deck A playing alone)
                       └────────────┬────────────┘
                                    │ Track queued & arm threshold reached
                                    ▼
                       ┌─────────────────────────┐
                       │   STATE_PREBUFFER_B     │ (FFmpeg decodes into Deck B ring)
                       └────────────┬────────────┘
                                    │ Prebuffer minimum met
                                    ▼
                       ┌─────────────────────────┐
                       │    STATE_ANALYZE        │ (Trim silence < -45 dBFS,
                       └────────────┬────────────┘  detect kick BPM & beat phase)
                                    │
                                    ▼
                       ┌─────────────────────────┐
                       │   STATE_MONITOR_OUTRO   │ (Track Deck A energy drop
                       └──────┬───────────┬──────┘  within 8/16-bar window)
                              │           │
     Outro energy drop / bar  │           │ User taps Next ("Fade Now")
     transition point reached │           │
                              ▼           ▼
         ┌────────────────────────┐   ┌────────────────────────┐
         │   STATE_FADING_AUTO    │   │   STATE_FADING_FAST    │
         │   (8 or 16 bars)       │   │   (4 bars)             │
         └────────────┬───────────┘   └───────────┬────────────┘
                      │                           │
                      └─────────────┬─────────────┘
                                    │
                                    ▼
                       ┌─────────────────────────┐
                       │   STATE_POST_RESTORE    │ (Deck B active; ramp tempo
                       └────────────┬────────────┘  back to 1.0x over 5.0s)
                                    │
                                    ▼
                       ┌─────────────────────────┐
                       │       STATE_IDLE        │ (Deck B becomes new Deck A)
                       └─────────────────────────┘
```

### 2.2 Silence Trimming (< -45 dBFS)
- **Threshold**: $-45\text{ dBFS} \approx 0.005623$ linear amplitude.
- **Deck B Leading Trim**: The native analyzer inspects decoded Float32 frames from Deck B and establishes the cue point at the first frame where root-mean-square (RMS) over a 10 ms window exceeds $-45\text{ dBFS}$. Deck B begins playback from this cue point, eliminating intro silence.
- **Deck A Trailing Trim**: Trailing frames falling continuously below $-45\text{ dBFS}$ for $>500\text{ ms}$ advance the effective track end so transitions do not fade into dead air.

### 2.3 Transition Timing & Outro Energy Drop Trigger
- **Bar Duration Formula**:
  $$\text{Bar Duration (seconds)} = 4 \times \frac{60}{\text{BPM}_A}$$
- **Total Fade Durations**:
  - **8 Bars**: $32 \times \frac{60}{\text{BPM}_A}$ (e.g., $15.0\text{ s}$ at 128 BPM; $21.3\text{ s}$ at 90 BPM).
  - **16 Bars**: $64 \times \frac{60}{\text{BPM}_A}$ (e.g., $30.0\text{ s}$ at 128 BPM; $42.6\text{ s}$ at 90 BPM).
  - **Fade Now (4 Bars)**: $16 \times \frac{60}{\text{BPM}_A}$ (e.g., $7.5\text{ s}$ at 128 BPM; $10.6\text{ s}$ at 90 BPM).
- **Outro Energy Drop Detection**:
  - Running short-time energy $E_{short}$ (0.5 s window) is evaluated against a 10-second running average $E_{avg}$.
  - When $E_{short} < 0.35 \times E_{avg}$ inside the final 8/16-bar window (or at the bar boundary), the automatic transition triggers.
- **Safety Cap**: Transition duration is clamped to a maximum of $\frac{1}{3}$ of the total track length to prevent overwhelming short tracks.

---

## 3. C++ WSOLA / SoundTouch Tempo & Beat Sync

### 3.1 Kick-Band BPM & Phase Detection (60–140 Hz)
- **Pre-Filtering**: Incoming stereo audio is summed to mono and passed through a 2nd-order Butterworth bandpass filter centered at 100 Hz ($Q = 1.0$, passband 60–140 Hz) to isolate kick drum transients.
- **Onset Envelope & Autocorrelation**:
  - Half-wave rectification and moving average smoothing generate the onset envelope.
  - Autocorrelation across lag intervals corresponding to 60–180 BPM ($333\text{ ms}$ to $1000\text{ ms}$) determines the primary tempo.
  - Beat phase $\phi$ is tracked as the sample offset to the downbeat peak.

### 3.2 Tempo Match Tolerance (±8%) & Harmonics
- **Tolerance Check**:
  - Direct: $|BPM_B - BPM_A| / BPM_A \le 0.08$
  - Half-time: $|2 \times BPM_B - BPM_A| / BPM_A \le 0.08$
  - Double-time: $|0.5 \times BPM_B - BPM_A| / BPM_A \le 0.08$
- If within tolerance, stretch ratio $\alpha = \frac{BPM_A}{BPM_{B,effective}}$ is locked and applied.
- If outside tolerance: Deck B plays at native $1.0\times$ speed (no time stretching). The transition proceeds smoothly using Linkwitz-Riley bass-swap and equal-power volume curves without beat sync.

### 3.3 WSOLA Time-Stretching & 5-Second Post-Mix Ramp
- **WSOLA Processor**: Stereo Float32 Waveform Similarity Overlap-Add algorithm:
  - Analysis frame size: 1024 samples with 50% overlap.
  - Search window: $\pm 256$ samples to locate maximal cross-correlation.
  - Hann-windowed overlap-add synthesis scales playback time by $\alpha$ while keeping vocal and instrumental pitch perfectly constant.
- **Beat Alignment**: Deck B's initial read pointer aligns so its first downbeat occurs synchronously with Deck A's downbeat.
- **5-Second Post-Mix Recovery Ramp**:
  - Upon completion of the crossfade (Deck A level reaches 0), Deck B's stretch factor $\alpha$ ramps linearly back to $1.0\times$ over $5.0\text{ seconds}$ ($5 \times \text{sampleRate}$ frames). This returns the track to its natural speed imperceptibly.

---

## 4. Linkwitz-Riley Bass-Swap & Echo Fade-Out Tail

### 4.1 Linkwitz-Riley 4th Order (LR4) Crossover at 160 Hz
- Formed by cascading two identical 2nd-order Butterworth biquad filters:
  - $LP_{LR4}(s) = [LP_{Butt2}(s)]^2$
  - $HP_{LR4}(s) = [HP_{Butt2}(s)]^2$
- Properties: $-6\text{ dB}$ attenuation at 160 Hz on both filters; in-phase sum yields an acoustically flat $0\text{ dB}$ response without phase cancellation.

### 4.2 Bass-Swap Progression (Equal-Power Curve)
During the transition interval $t \in [0, T]$:
- **Phase 1 ($0 \le t < 0.5 T$, First Half)**:
  - **Deck A**: Full spectrum (Bass + Mids/Highs). Equal-power volume curve $\cos\left(\frac{\pi}{2} \cdot \frac{t}{T}\right)$.
  - **Deck B**: 160 Hz High-Pass filter enabled (all low-end $<160\text{ Hz}$ cut). Mids/Highs fade in via $\sin\left(\frac{\pi}{2} \cdot \frac{t}{T}\right)$.
  - Prevents kick clash and low-frequency mud.
- **Midpoint Downbeat Swap ($t = 0.5 T$)**:
  - Exactly on the midpoint downbeat:
  - Deck B 160 Hz High-Pass filter is deactivated (Deck B gains full low-end bass).
  - Deck A 160 Hz High-Pass filter is activated (Deck A loses its low-end bass).
- **Phase 2 ($0.5 T \le t \le T$, Second Half)**:
  - **Deck B**: Full spectrum continuing fade in to 100%.
  - **Deck A**: Bass cut, continuing fade out to 0%.

### 4.3 1.2-Second Echo / Reverb Fade-Out Tail on Deck A
- **Feedback Delay Network**:
  - Delay line length: tempo-synced to $1/2$ or $1/4$ beat of Deck A (clamped between $200\text{ ms}$ and $500\text{ ms}$).
  - Feedback attenuation: set for $T_{60} = 1.2\text{ seconds}$ decay time.
  - Low-pass damping filter in feedback loop at $4.5\text{ kHz}$ for warm, natural dissipation.
- **Application**: Deck A's dry signal fades down while feeding the echo buffer during the second half of the transition, allowing Deck A to dissolve gracefully into ambient space as Deck B assumes center stage.

---

## 5. System Architecture & Native Integration

```
[ ExoPlayer A (FFmpeg) ] ──> [ NativeProcessingAudioSink A ] ──┐
                                                               │ (Float32 Stereo PCM)
                                                               ▼
[ ExoPlayer B (FFmpeg) ] ──> [ NativeProcessingAudioSink B ] ──> [ AutoMixEngine (C++) ]
                                                                       │
                                                                 • Dual-Deck State Machine
                                                                 • Kick BPM & Phase Sync
                                                                 • WSOLA Pitch-Locked Stretch
                                                                 • LR4 160Hz Bass-Swap
                                                                 • 1.2s Echo Fade-Out Tail
                                                                       │
                                                                       ▼
                                                             [ AudioEngine / Oboe Out ]
```

### 5.1 C++ Native Components (`app/src/main/cpp/`)
1. `LinkwitzRileyFilter.h`: 4th-order Linkwitz-Riley 160 Hz LP/HP biquad crossover.
2. `WsolaTimeStretcher.h` & `WsolaTimeStretcher.cpp`: Real-time Float32 stereo pitch-preserving WSOLA time-stretcher.
3. `BpmPhaseDetector.h` & `BpmPhaseDetector.cpp`: 60–140 Hz kick bandpass filter and autocorrelation tempo/phase estimator.
4. `EchoTailProcessor.h`: 1.2-second tempo-synced feedback delay/reverb processor.
5. `AutoMixEngine.h` & `AutoMixEngine.cpp`: Master Automix coordinator managing dual-deck buffers, state machine transitions, silence trimming, and mixing.
6. `AutoMixBridge.cpp`: JNI interface exposing control, status, and configuration to Kotlin.

### 5.2 Kotlin / Playback Integration (`app/src/main/java/com/lastwave/app/`)
1. **Preferences & Mutual Exclusivity** (`SettingsPreferences.kt`):
   - `AUTOMIX_ENABLED` (Boolean, default `false`).
   - `AUTOMIX_BARS` (Int, 8 or 16, default `8`).
   - Mutual exclusivity: toggling `AUTOMIX_ENABLED = true` automatically sets `CROSSFADE_ENABLED = false`. Toggling `CROSSFADE_ENABLED = true` automatically sets `AUTOMIX_ENABLED = false`.
2. **Playback Engine & Scheduling** (`MusicPlayer.kt`, `AutoMixSchedule.kt`):
   - Pre-buffers next track on Deck B via existing FFmpeg-backed ExoPlayer instance when Automix is enabled.
   - Monitors live position and outro energy.
   - Intercepts "Next" command when Automix is active to trigger "Fade Now" (4-bar blend) instead of immediate skip.
3. **UI Integration** (`SettingsScreen.kt`, `PlayerHost.kt`):
   - Dedicated "Automix (AutoDJ)" toggle and Bar Selector (8 Bars / 16 Bars) in Settings.
   - Distinct Automix indicator / badge on Now Playing screen.

---

## 6. Verification & Non-Regression Rules
- **No Gradle Builds**: Comply strictly with user constraint (`dont gradle build`).
- **Bit-Perfect Safety**: When Bit-Perfect output is enabled, Automix is automatically bypassed (same as standard crossfade).
- **Zero Allocations in Real-Time Callback**: All ring buffers, scratch spaces, and delay lines are pre-allocated during configuration.
