# LastWave 5.3.0 release preparation report

Scope: merged pull requests and integration branch merges after published **5.2.1**, through main `75e6bdcb8a5fc3d8898f6d26ae58728d8c104223` on November 24, 2026. Individual/direct-main commits are outside this requested analysis. Final source was checked so later reversions and replacements are reflected below.

## New features and enhancements

1. **AutoMix Pro:** Musical audio analysis, beat/phrase alignment, bass exchange and lyric-aware cues, with a studio popup showing both tracks, transition waveforms and the playhead. **Ayush / Pyrexus** (#706).
2. **Player Lyrics:** Synchronized lyrics inside Now Playing, with saved Auto/Center alignment and the existing provider, translation, size and timing options. **Ayush** (#639).
3. **Signature Lyrics Card:** LastWave wordmark, song details, provider credits and animated loading presentation for intros/loading/unavailable lyrics. **Ayush** (#647, #648).
4. **Flow & Halo:** Two new selectable lyrics styles and an in-player style chooser. **Shoyo / Nox-Wizard-py** (#673).
5. **Letter-Level Lyrics Motion:** A letter-by-letter lift using existing word/syllable timing, with joined-script safeguards. **Ayush** (#698).
6. **Lyra Beat-Synced Artwork:** Optional audio-driven fluid movement, refined to follow audible playback across songs and speeds. **Dux Tami / duxtami**, **Sahan / Sahank29**, **Ayush** (beat-background integration; #687, #710, #666, #667).
7. **Listening Insights & Stats Motion:** Records, streaks, comparisons, hourly/activity charts, a collapsing Stats header and All Time-first range ordering. **Dux Tami and Ayush** (legacy/stats integration; #654, #690).
8. **Legacy Colours:** An option restoring the earlier dynamic dark-surface treatment. **Dux Tami** (legacy/stats integration).
9. **ListenBrainz & Libre.fm:** Independent additional scrobblers and compatible-server configuration beside Last.fm. **Ayush** (#711).
10. **Waveform Seekbar:** A new flowing progress style with interactive previews before selecting a seekbar. **Shoyo** (#613).
11. **Headphone Music Recognition:** Internal audio recognition for permitted other-app playback while wired/Bluetooth headphones are connected. **Shoyo** (#741).
12. **AutoEQ Import & Export:** Exchange parametric-EQ configuration and enter frequency, gain and Q numerically. **Ayush** (#717).
13. **Pause at Zero Volume:** Optional automatic pause and controlled resume when system media volume returns. **Shoyo** (#682).
14. **Notification Repeat:** Off/All/One cycling replaces the notification Shuffle action. **Shoyo** (#704).
15. **Home & Library Enhancements:** Artist Spotlight paging, hideable Quick access, connected Local songs/Downloads pills, and refined menus/haptics/lyrics settings. **Ayush and Dux Tami**, with tablet artwork refinements by **Sahan** (#693, #650, #633, #641, #688, #694, #695, #683; custom-UI integration).
16. **Completed Spanish Translations:** Completed and expanded Spanish resources. **Pedro Flores / PedroFlores199** (#672).

## Fixes and optimizations

- **Startup & Performance:** Less launch work, repeated settings processing, off-screen animation, player redraws, audio allocation and unnecessary network/storage work (#607, #616, #622, #644, #645, #670).
- **Home Feed:** Earlier useful content, reliable refresh, stable section positions and pagination (#643, #666, #669).
- **Queue & Repeat:** Stable drag reordering; stop at a failed playlist end with repeat off; fresh shuffle order in repeat-all rounds (#674, #655).
- **Google Cast:** Next-song playback no longer sends an unnecessary Stop to an already finished receiver (#701).
- **Lyrics:** Prompt clearing on track changes, non-Latin bold text, clipping fixes, signature-card placement and selective artwork blur (#619, #697, #673, #634, #648, #649, #653).
- **Tablet Player:** One set of controls, correct Full Screen Artwork behavior, artwork retained beside centered fullscreen lyrics (#617, #683, #727).
- **Recommendations & Artists:** Better Spotify recording matches, recommendation exclusions and recognized band names with separators (#629, #676, #700).
- **Addon Playback:** Play with enabled addons without YouTube sign-in, with a full addon-resolution budget after YouTube fails (#725).
- **USB DAC & Atmos:** Decoded audio routing to USB and correct interface/kernel-driver release when exclusive output closes (#715, #720).
- **Library & Likes:** Local artwork fallback, newest-first likes, playlist sorting and shuffle-start refinements (custom-UI integration).
- **UI & Video:** Card proportions, tighter spacing, mini-player artwork and video chrome; profile-image and keyboard/focus fixes; removal of redundant header/player buttons (#666, #669, #705, #709, #716, #724, #741, #742).
- **OriginOS Lyrics:** More reliable Island/widget lyrics and consistency with the chosen provider (#651).
- **Backups & Diagnostics:** More complete audio settings/configuration restore and cleanup of old diagnostics exports (#691, #692).
- **Recognition Stability:** Required foreground-capture handling and cleanup for Android 14+ (#741).

## Wording limits for the Telegram post and release notes

- AutoMix, Artwork Beat Sync and Pause at Zero Volume are optional and off by default. The Lyra effect requires Android 13+ and supported rendering.
- The Waveform seekbar is a progress animation. The AutoMix studio shows the transition window, rather than a whole-song waveform.
- Letter animation uses existing timings; it is not a new letter-timing provider. Flow/Halo and connected scripts retain their appropriate whole-word motion.
- Headphone recognition requires Android 10+, capture consent and an app that permits capture. Older/declined cases use the microphone.
- Stats insights include estimates/fallbacks. Describe the charts and comparisons without promising exact session history.
- Do not repeat the original 59-second playback/download and FLAC-seeking fixes from 5.2.1. Do not count PRs as fixed issues or claim an unverified 50+ issue total.
- Reverted/superseded features and fixes are omitted. The detailed reports explain exclusions, including #642, #678, #699, the progress-bar portion of #717 and the original #459 backup policy.

## Contributor credits

Main contributors: **Ayush, Dux Tami, Sahan and Shoyo**.

Additional merged work: **Pan-TheMechanic**, **JacksonJones2003**, **NONDEADER**, **PedroFlores199**, **Simone20031** and **xppenTC**. See [CREDITS.md](CREDITS.md) for attribution by feature/fix.

## Evidence and verification

- [FEATURE_AUDIT.md](FEATURE_AUDIT.md): prioritized features, original authors, source references and constraints.
- [FIX_AUDIT.md](FIX_AUDIT.md): grouped fixes/performance and final-state evidence.
- [MERGED_CHANGES.md](MERGED_CHANGES.md): merged-PR/branch inventory.
- [release-audit.json](release-audit.json): structured merged-change metadata.
- Build and artifact verification are recorded separately in RELEASE_MANIFEST.json and TEST_RESULTS.json when complete. No device playback or live-service verification has been performed.
