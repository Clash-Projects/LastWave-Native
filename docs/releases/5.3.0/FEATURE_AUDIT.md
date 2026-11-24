# LastWave 5.3.0 — merged feature analysis

Baseline: published **5.2.1**, tag commit `6db7b99bb2fdeb052f59e3cc1a5e284feac43ee0`. Cutoff: main commit `75e6bdcb8a5fc3d8898f6d26ae58728d8c104223`, November 24, 2026. This report covers merged pull requests and feature-branch integrations after that baseline. Direct changes on main are excluded from the feature list, following the requested scope.

The ordering below reflects how substantial the resulting user-facing change is. Features and enhancements are kept separate from bug fixes. Claims were checked against the final source, including later replacements and reversions; this is not a device verification report.

## Major features and enhancements, in priority order

### 1. AutoMix Pro: musical transitions and a live studio view

**Enhanced existing AutoMix.** AutoMix now analyzes decoded audio from both songs for tempo, beat/bar grids, key, silence and ending shape. When the songs are compatible, it aligns phrases and live kicks, opens the incoming song's highs, exchanges bass around the middle downbeat, and steers the combined level. Synced lyrics inform cue selection to reduce overlapping vocals and cuts through sung lines. Uncertain matches use an ending-aware fade.

The quality badge opens a centered studio popup near the transition, showing the two tracks, their cue-window waveforms, tempo/key information, mix area and moving playhead. This is the strongest headline change in the merged release range.

- Merge: [#706](https://github.com/Clash-Projects/LastWave-Native/pull/706).
- Credit: **Ayush / @Pyrexus**.
- Final source: `playback/automix/analysis/TrackAnalyzer.kt`, `engine/TransitionPlanner.kt`, `engine/DualDeckTransportCoordinator.kt`, `engine/DeckEqStage.kt`, `engine/KickTap.kt`; `ui/player/automix/AutoMixPopup.kt` and `AutoMixWaveforms.kt`.
- Limits: AutoMix remains off by default. Beat matching requires confident grids, normal user speed/pitch and close tempos; it is bypassed for podcasts, short tracks, consecutive album tracks, repeat-one, Listen Together and incompatible output modes. The studio popup is available in the final 25 seconds and briefly after completion. Final waveforms cover the transition window, not entire songs.

### 2. Player Lyrics directly on Now Playing

**New mode.** The Lyrics button can place synchronized lyrics inside the main player artwork area. It shares the existing lyrics renderer and options, including provider selection, translation, romanization, text size, sync offset and sharing. Artwork, Spotify Canvas and music video retain their intended presentation while lyrics are open.

The Full Screen action cycles through Player Lyrics, the lyrics screen, full-screen lyrics and back; Back steps outward through the same flow. A saved **Auto / Center** alignment choice applies across lyrics views. Auto retains singer-side staging where available; Center puts rows in the middle.

- Merge: [#639](https://github.com/Clash-Projects/LastWave-Native/pull/639).
- Credit: **Ayush / @Pyrexus**.
- Final source: `ui/player/PlayerLyrics.kt` (`SyncedLyricsProgressHost`), `PlayerHost.kt`, `FullPlayer.kt`, `ModernLyricsControls.kt`; saved `lyricsCentered` preference in `data/local/SettingsPreferences.kt`.
- Limit: this is a Now Playing mode, not lyrics on the Feed home screen. The tablet two-column landscape layout follows its separate layout flow.

### 3. Signature lyrics card, two lyrics styles and smoother letter motion

**New presentation choices.** The LastWave signature card adds the glowing wordmark, song information, provider credit and equalizer-style loading treatment to the intro, loading and unavailable-lyrics states. **Flow** and **Halo** add two selectable lyrics presentations alongside Default, with different typography, line scaling, blur and word motion. Animated music-note indicators accompany instrumental intros and gaps.

The default word-synced lift now travels through a word letter by letter. It improves motion using existing syllable timing; it does not add a new timing provider or invent letter timestamps.

- Merges: [#647](https://github.com/Clash-Projects/LastWave-Native/pull/647), [#648](https://github.com/Clash-Projects/LastWave-Native/pull/648), [#673](https://github.com/Clash-Projects/LastWave-Native/pull/673), [#698](https://github.com/Clash-Projects/LastWave-Native/pull/698).
- Credit: **Ayush / @Pyrexus** for the signature card and letter lift; **Shoyo / @Nox-Wizard-py** for Flow, Halo and the style chooser.
- Final source: `ui/player/LyricsSignature.kt`, `LyricsStaging.kt`, `LyricsTheme.kt`, `LyricsStyleDialog.kt`, `InstrumentalBreakItem.kt`, `ModernLyricsKaraokeCanvas.kt`.
- Limits: Default is the saved default. Hardware depth blur requires Android 12+, with fallback on older versions. Flow's wobble and Halo's glide retain whole-word motion; non-Latin/joined scripts also retain whole-word lift to protect their connected glyphs. Velvet was removed and is not a shipped third new style.

### 4. Lyra-inspired beat-synced fluid artwork

**New optional background.** Bass and midrange levels from playback drive swells, movement and brightness in the fluid artwork background. Later merged work aligns the visual envelope with audible playback and improves response across different songs and playback speeds.

- Integration: [`feat/beat-synced-fluid-motion`](https://github.com/Clash-Projects/LastWave-Native/commit/2818d42c189f926c2eb6c23fdd256c3b5937a7b0).
- Follow-up merges: [#687](https://github.com/Clash-Projects/LastWave-Native/pull/687), [#710](https://github.com/Clash-Projects/LastWave-Native/pull/710), [#666](https://github.com/Clash-Projects/LastWave-Native/pull/666), [#667](https://github.com/Clash-Projects/LastWave-Native/pull/667).
- Credit: **@duxtami** for the original integrated rhythm background; **Sahan / @Sahank29** for final flow and playback response; **Ayush / @Pyrexus** for opt-in/default-background isolation.
- Final source: `ui/player/BeatSyncFluidBackground.kt`, `FluidArtworkBackground.kt` (the Android-version gate); `playback/rhythm/FluidRhythmEngine.kt`, `haptics/MusicHapticsAnalyzer.kt`.
- Limits: **Artwork Beat Sync is off by default**. Its final shader requires **Android 13+**, supported hardware and the fluid/rotating background. Android 12 keeps the normal fluid renderer; unsupported devices use the existing fallback. Avoid an unconditional “works on every device/output” claim.

### 5. ListenBrainz and Libre.fm scrobblers

**Two new services.** Settings now groups the existing Last.fm integration with ListenBrainz and Libre.fm under Scrobblers. Each provider has independent connection state and compatible-server support. Playback can scrobble without connecting Last.fm, and a failed provider does not block successful submissions to another.

The existing backup/restore flow includes the new providers' tokens/session credentials and server choices. Libre.fm passwords are used for sign-in and are not stored.

- Merge: [#711](https://github.com/Clash-Projects/LastWave-Native/pull/711).
- Credit: **Ayush / @Pyrexus**.
- Final source: `data/scrobbler/ExtraScrobblers.kt`, `ExtraScrobblerPreferences.kt`, `data/repository/ScrobbleRepository.kt`, `ui/settings/SettingsScrobblersSection.kt`, `data/backup/BackupRepository.kt`.
- Limit: pending submissions are held in bounded in-memory queues, not persistent offline queues that survive restarting the app. Live provider accounts were not tested by this audit.

### 6. Rhythm insights and Legacy Colours

**New insights within the existing Stats screen; new appearance option.** The merged stats expansion adds a Week range, personal records, listening streaks, weekday/weekend comparisons, month comparisons, an hourly chart and an activity heatmap. The Stats header can condense into a compact row while scrolling and expand again on returning to the top or tapping it. The time range chips now put All Time first.

**Legacy Colours** restores the earlier dynamic dark-surface treatment instead of the new fixed muted dark surfaces.

- Integration: [`feat/legacy-colors-and-stats-v2`](https://github.com/Clash-Projects/LastWave-Native/commit/961835c56632ece634a1179ac274a97439f117c2).
- Related merges: [#654](https://github.com/Clash-Projects/LastWave-Native/pull/654), [#690](https://github.com/Clash-Projects/LastWave-Native/pull/690).
- Credit: **@duxtami** for the stats insights and Legacy Colours; **Ayush / @Pyrexus** for the collapsing header and range ordering.
- Final source: `ui/stats/ListeningStatsModels.kt`, `ListeningStatsViewModel.kt`, `ListeningStatsComponents.kt`, `ListeningStatsScreen.kt`; `ui/home/StatsCollapse.kt`; `data/local/ThemePreferences.kt`, `ui/theme/Md3SchemeBuilder.kt`.
- Limits: the initial dedicated Listening Stats screen was added directly on main, so it is not labeled a merged new feature here. Several final stats values and fallback dates are estimates derived from aggregate track data; do not promise exact historical sessions, precise time-range totals or measured “focus states.” Legacy Colours is off by default.

### 7. Waveform seekbar with interactive style previews

**New seekbar style.** A One UI-inspired flowing Waveform slider joins Default and Wavy. Its waves react to playback/pause and scrubbing and use artwork colors. The style chooser lets users scrub live previews before applying a selection, with backward migration for the previous Wavy setting.

- Merge: [#613](https://github.com/Clash-Projects/LastWave-Native/pull/613).
- Credit: **Shoyo / @Nox-Wizard-py**.
- Final source: `ui/player/WaveformSlider.kt`, `PlayerSeekBar.kt`, `ui/settings/SettingsDialogs.kt`; `SeekbarStyle` and migration in `data/local/SettingsPreferences.kt`.
- Limits: Default remains default; an existing Wavy choice stays Wavy. This is a decorative waveform progress animation, not the song's decoded amplitude waveform.

### 8. Music recognition through headphones and Bluetooth

**Enhanced recognition.** With compatible headphones connected, recognition can capture internal playback from other apps instead of relying on the microphone. The Search screen remains the entry point after the Home and Stats header buttons were removed.

- Merge: [#741](https://github.com/Clash-Projects/LastWave-Native/pull/741).
- Credit: **Shoyo / @Nox-Wizard-py** for the merged internal-capture enhancement; the earlier microphone recognition foundation was by **Ajisth / @ajisth69** directly on main.
- Final source: `data/recognition/AudioSourceDetector.kt`, `MicCapture.kt`, `AudioPlaybackCaptureService.kt`; `ui/recognition/MusicRecognitionViewModel.kt`, `MusicRecognitionButton.kt`, `ui/search/SearchScreen.kt`.
- Limits: internal capture needs Android 10+ and screen/audio capture consent; the source app must permit playback capture. Older Android versions or declined capture consent fall back to the microphone. Do not promise capture from every app.

### 9. AutoEQ import/export and direct PEQ values

**New editing controls.** Import a parametric EQ configuration by pasted text, clipboard or file picker; export it as text to the clipboard and share sheet. Expanded filters now accept numeric frequency, gain and Q input alongside the sliders.

- Merge: [#717](https://github.com/Clash-Projects/LastWave-Native/pull/717), feature portion implementing #680.
- Credit: **Ayush / @Pyrexus**.
- Final source: `ui/settings/autoeq/AutoEqScreen.kt`, `AutoEqViewModel.kt` (`importSettings`, `exportSettings`), `data/autoeq/PeqModel.kt` (`ParametricEqText`) and `AutoEqController.kt`.
- Limit: this adds configuration exchange and editing, not a new AutoEQ engine. Import applies parsed custom settings and enables AutoEQ.

### 10. Pause at zero volume with controlled auto-resume

**New optional playback behavior.** When enabled, lowering system media volume to zero pauses playback. Raising it resumes only a track paused by that zero-volume action, with a minimum 10% volume floor. Manual or external transport actions clear the auto-resume state, and play/resume while muted is intercepted.

- Merge: [#682](https://github.com/Clash-Projects/LastWave-Native/pull/682), implementing #625.
- Credit: **Shoyo / @Nox-Wizard-py**.
- Final source: `playback/ZeroVolumePlaybackObserver.kt`, `MusicPlayer.kt`; `pauseOnZeroVolume` in `data/local/SettingsPreferences.kt`, `ui/settings/SettingsScreen.kt`.
- Limits: off by default; observes system media volume/mute, not just LastWave's own volume slider. Auto-resume can raise a very small positive system volume to the 10% floor.

### 11. Repeat controls in the media notification

**New notification action.** The outer Shuffle slot becomes a stateful Repeat control, cycling Off → All → One → Off and keeping its icon synchronized with the player. Like remains in the other outer slot.

- Merge: [#704](https://github.com/Clash-Projects/LastWave-Native/pull/704), implementing #685.
- Credit: **Shoyo / @Nox-Wizard-py**.
- Final source: `playback/NotificationRepeatAction.kt`, `MusicPlaybackServiceNotification.kt`, `MusicPlaybackServiceSession.kt`, `MusicPlayer.kt`; `res/layout/notification_player_expanded.xml`.
- Limit: describe a replacement of the notification action, not an additional Shuffle-plus-Repeat pair.

### 12. Home controls, connected Library pills and presentation polish

**Smaller enhancements suitable for one grouped release point.** Artist Spotlight pages through up to five artists with a five-second auto-slide, pausing during interaction and when not visible/resumed. Quick access can be hidden by long-press and restored in Home Layout & Sections. Local songs and Downloads return to a connected side-by-side pill row above the playlists. The merged player menus, haptics controls and lyrics options/settings layouts are more compact, and tablet artwork follows the Full Screen Artwork choice.

- Merges: [#693](https://github.com/Clash-Projects/LastWave-Native/pull/693), [#650](https://github.com/Clash-Projects/LastWave-Native/pull/650), [#633](https://github.com/Clash-Projects/LastWave-Native/pull/633), [#641](https://github.com/Clash-Projects/LastWave-Native/pull/641), [#688](https://github.com/Clash-Projects/LastWave-Native/pull/688), [#694](https://github.com/Clash-Projects/LastWave-Native/pull/694), [#695](https://github.com/Clash-Projects/LastWave-Native/pull/695), [#683](https://github.com/Clash-Projects/LastWave-Native/pull/683); [`feat/custom-ui-fixes-and-improvements`](https://github.com/Clash-Projects/LastWave-Native/commit/2b1e2b763c29226a9ebfb1fa57dacf412d77e683).
- Credits: **Ayush / @Pyrexus** for Home controls and the merged menu/lyrics/haptics layouts; **@duxtami** for the integrated connected Library pills and associated layouts; **Sahan / @Sahank29** for tablet artwork behavior.
- Final source: `ui/feed/FeedScreen.kt`, `FeedShelves.kt` (`SpotlightPager`), `ui/settings/HomeSectionsScreen.kt`, `ui/playlist/PlaylistScreen.kt`, `PlaylistCards.kt`, `ui/common/TrackContextMenuSheet.kt`, `ui/player/AudioControlsSheet.kt`, `ModernLyricsControls.kt`, `FullPlayerLandscape.kt`.
- Limits: the Local Music hub and Downloads in Library already existed in earlier releases. This is layout refinement, not a new local-library feature. The final default song menu is the restored #676 design; the saved “classic” option selects the alternate sheet, not the removed original card menu.

### 13. Completed Spanish translations

**Localization enhancement.** Spanish strings were completed and extended, including the moods screen.

- Merge: [#672](https://github.com/Clash-Projects/LastWave-Native/pull/672).
- Credit: **Pedro Flores / @PedroFlores199**.
- Final source: `res/values-es/strings.xml`, `res/values-es/moods_screen.xml`.
- Limit: Spanish resources existed before this range; say “completed Spanish translations,” not “first Spanish support.” Chinese translations were a direct-main addition, so they are excluded from this merged-only feature list.

## Claims to leave out of the merged release analysis

- **Brand-new Listening Stats screen, Preview Queues, four-shade accent previews, initial microphone recognition and in-app APK update installation:** their initial implementations are direct-main changes. Only the independently merged follow-up enhancements above are included.
- **New animated Your Genres spectrum:** #678 was merged but the spectrum was subsequently restored to the prior layout. Final `GenresScreen.kt` differs from the baseline only in menu capability cleanup.
- **Whole-song AutoMix waveforms:** an intermediate implementation was replaced with transition-window decoding and drawing.
- **Velvet lyrics style:** removed before the final cutoff; shipped styles are Default, Flow and Halo.
- **New letter-level lyric timing:** the added effect animates existing word/syllable timestamps, with script/style safeguards.
- **New music haptics, local music hub, adaptive streaming, or the original 59-second playback fix:** those are earlier features. The relevant post-5.2.1 merged improvements belong in the separate fixes/performance analysis.

## Verification limits

Merged request descriptions, integration diffs and final source were reviewed. No app code was changed for this report. No Gradle build, device test, live scrobbler connection, headphone recognition session or listening verification was performed by this audit; build verification is being handled separately.
