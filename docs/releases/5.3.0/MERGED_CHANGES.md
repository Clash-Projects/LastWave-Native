# LastWave 5.3.0 merged changes

Baseline: [5.2.1](https://github.com/Clash-Projects/LastWave-Native/releases/tag/5.2.1). Cutoff: [`75e6bdcb`](https://github.com/Clash-Projects/LastWave-Native/commit/75e6bdcb8a5fc3d8898f6d26ae58728d8c104223) on November 24, 2026.

This inventory covers merged pull requests whose merge commits are in the exact release range, plus the three integration branch merges. Direct-main commits are outside the requested analysis. Final source was checked to avoid announcing reverted or superseded behavior.

| Merge | Contributor | Group | Change | Final-state note |
| --- | --- | --- | --- | --- |
| [#611](https://github.com/Clash-Projects/LastWave-Native/pull/611) | @Pyrexus | Maintenance / release preparation | chore(release): LastWave 5.2.1 patch | 5.2.1 preparation merge after the tag; excluded from 5.3.0 product claims. |
| [#607](https://github.com/Clash-Projects/LastWave-Native/pull/607) | @Sahank29 | Performance | Perf/playback cpu |  |
| [#622](https://github.com/Clash-Projects/LastWave-Native/pull/622) | @Pyrexus | Performance | perf: smoother app on low-end and high-end devices |  |
| [#617](https://github.com/Clash-Projects/LastWave-Native/pull/617) | @Sahank29 | Fix / refinement | fix(player): one set of controls in the tablet lyrics split |  |
| [#618](https://github.com/Clash-Projects/LastWave-Native/pull/618) | @Sahank29 | New feature / enhancement | fix(player): swipe down closes the player from lyrics |  |
| [#619](https://github.com/Clash-Projects/LastWave-Native/pull/619) | @Sahank29 | Fix / refinement | fix(lyrics): don't keep the previous song's lyrics on track change |  |
| [#633](https://github.com/Clash-Projects/LastWave-Native/pull/633) | @Pyrexus | New feature / enhancement | Redesign player Music Haptics controls |  |
| [#634](https://github.com/Clash-Projects/LastWave-Native/pull/634) | @Pyrexus | Fix / refinement | fix(lyrics-share): YouTube Music style colours, real lyric font, bolder wordmark |  |
| [#613](https://github.com/Clash-Projects/LastWave-Native/pull/613) | @Nox-Wizard-py | New feature / enhancement | feat: add One UI waveform seekbar and interactive seekbar style chooser |  |
| [#629](https://github.com/Clash-Projects/LastWave-Native/pull/629) | @Simone20031 | Fix / refinement | fix(recommend): enforce duration and featured-artist matching for Spotify tracks |  |
| [#639](https://github.com/Clash-Projects/LastWave-Native/pull/639) | @Pyrexus | New feature / enhancement | feat(player): inline Player Lyrics on Now Playing |  |
| [#616](https://github.com/Clash-Projects/LastWave-Native/pull/616) | @Sahank29 | Performance | perf(player): cut full player redraw cost |  |
| [#641](https://github.com/Clash-Projects/LastWave-Native/pull/641) | @Pyrexus | New feature / enhancement | style(lyrics): redesign the Lyrics options dialog |  |
| [#642](https://github.com/Clash-Projects/LastWave-Native/pull/642) | @Pyrexus | Reverted / superseded claim | fix(playlists): show your playlists when adding a device song | Add-to-existing-playlist change was reverted; excluded from final fixes. |
| [#643](https://github.com/Clash-Projects/LastWave-Native/pull/643) | @Sahank29 | Fix / refinement | Fix home feed loading and refresh stability |  |
| [#644](https://github.com/Clash-Projects/LastWave-Native/pull/644) | @Sahank29 | Performance | perf: fix launch freeze (settings flow on main, off-screen tabs) |  |
| [#645](https://github.com/Clash-Projects/LastWave-Native/pull/645) | @Sahank29 | Performance | perf(settings): map settings once off the main thread |  |
| [#647](https://github.com/Clash-Projects/LastWave-Native/pull/647) | @Pyrexus | New feature / enhancement | feat(lyrics): LastWave signature intro card |  |
| [#648](https://github.com/Clash-Projects/LastWave-Native/pull/648) | @Pyrexus | Fix / refinement | fix(lyrics): signature intro polish |  |
| [#649](https://github.com/Clash-Projects/LastWave-Native/pull/649) | @Pyrexus | Fix / refinement | fix(player): light blur on full-screen art behind Player Lyrics |  |
| [#650](https://github.com/Clash-Projects/LastWave-Native/pull/650) | @Pyrexus | New feature / enhancement | feat(home): hide Quick access via long-press and settings toggle |  |
| [#653](https://github.com/Clash-Projects/LastWave-Native/pull/653) | @Pyrexus | Fix / refinement | fix(player): limit the Player Lyrics art blur to the lyrics area |  |
| [#654](https://github.com/Clash-Projects/LastWave-Native/pull/654) | @Pyrexus | New feature / enhancement | feat(stats): condense the stats card into a row while the list scrolls |  |
| [#459](https://github.com/Clash-Projects/LastWave-Native/pull/459) | @xppenTC | Reverted / superseded claim | fix(backup): keep addon access tokens out of backup files | Token-exclusion policy was superseded by the final complete-backup implementation; no security-hardening claim. |
| [#655](https://github.com/Clash-Projects/LastWave-Native/pull/655) | @Pyrexus | Fix / refinement | fix(playback): stop at playlist end; reshuffle each repeat-all round |  |
| [#666](https://github.com/Clash-Projects/LastWave-Native/pull/666) | @Pyrexus | Fix / refinement | fix(feed,video,player): fast feed load, uniform shelf cards, clean video frame, beat sync opt-in |  |
| [#667](https://github.com/Clash-Projects/LastWave-Native/pull/667) | @Pyrexus | Fix / refinement | fix(player): isolate beat-synced fluid; default player back to its original background |  |
| [#669](https://github.com/Clash-Projects/LastWave-Native/pull/669) | @Pyrexus | Fix / refinement | fix(feed,player): page while loading, even Quick picks, 16:9 cards, fading header label |  |
| [#672](https://github.com/Clash-Projects/LastWave-Native/pull/672) | @PedroFlores199 | Localization | feat(i18n): complete Spanish translations |  |
| [#673](https://github.com/Clash-Projects/LastWave-Native/pull/673) | @Nox-Wizard-py | New feature / enhancement | feat: add Flow and Halo lyrics styles with in-player style chooser |  |
| [#674](https://github.com/Clash-Projects/LastWave-Native/pull/674) | @Pyrexus | Fix / refinement | fix(queue): stop drag-reorder pulling neighbouring songs |  |
| [#676](https://github.com/Clash-Projects/LastWave-Native/pull/676) | @Pyrexus | New feature / enhancement | Polish player song menu and honor YouTube recommendation exclusions |  |
| [#663](https://github.com/Clash-Projects/LastWave-Native/pull/663) | @app/dependabot | Maintenance / release preparation | deps: bump androidx.core:core-ktx from 1.13.1 to 1.19.1 |  |
| [#662](https://github.com/Clash-Projects/LastWave-Native/pull/662) | @app/dependabot | Maintenance / release preparation | deps: bump com.google.android.material:material from 1.12.0 to 1.14.0 |  |
| [#660](https://github.com/Clash-Projects/LastWave-Native/pull/660) | @app/dependabot | Maintenance / release preparation | deps: bump org.robolectric:robolectric from 4.12.2 to 4.18 |  |
| [#659](https://github.com/Clash-Projects/LastWave-Native/pull/659) | @app/dependabot | Maintenance / release preparation | deps: bump room from 2.7.1 to 2.8.5 |  |
| [#658](https://github.com/Clash-Projects/LastWave-Native/pull/658) | @app/dependabot | Maintenance / release preparation | ci: bump actions/setup-java from 6.2.0 to 6.3.0 |  |
| [#678](https://github.com/Clash-Projects/LastWave-Native/pull/678) | @Pyrexus | Reverted / superseded claim | Redesign Your Genres with an animated Material 3 spectrum | Animated genre spectrum was restored to the prior design; excluded from final features. |
| [#677](https://github.com/Clash-Projects/LastWave-Native/pull/677) | @Pyrexus | Maintenance / release preparation | fix(menu): remove the internal MotionScheme usage that broke main's build |  |
| [#670](https://github.com/Clash-Projects/LastWave-Native/pull/670) | @Sahank29 | Performance | perf: keep BotGuard WebView and per-call regexes out of app launch |  |
| [#682](https://github.com/Clash-Projects/LastWave-Native/pull/682) | @Nox-Wizard-py | New feature / enhancement | feat: pause music playback automatically at zero volume (#625) |  |
| [#688](https://github.com/Clash-Projects/LastWave-Native/pull/688) | @Pyrexus | New feature / enhancement | style(lyrics): make lyrics options dialog more compact in height |  |
| [#689](https://github.com/Clash-Projects/LastWave-Native/pull/689) | @Pyrexus | New feature / enhancement | style(shell): reorder quick actions panel items |  |
| [#690](https://github.com/Clash-Projects/LastWave-Native/pull/690) | @Pyrexus | New feature / enhancement | style(stats): display All Time first in listening stats time range chips |  |
| [#691](https://github.com/Clash-Projects/LastWave-Native/pull/691) | @Pyrexus | Fix / refinement | fix(backup): include float prefs and EQ/loudness/automix/USB datastores |  |
| [#692](https://github.com/Clash-Projects/LastWave-Native/pull/692) | @Pyrexus | Fix / refinement | fix(export): prune old timestamped diagnostics reports |  |
| [#687](https://github.com/Clash-Projects/LastWave-Native/pull/687) | @Sahank29 | Fix / refinement | fix(player): make beat-synced background match Lyra's flow | Lyra behavior was further refined by #710; report as one combined beat-sync improvement. |
| [#693](https://github.com/Clash-Projects/LastWave-Native/pull/693) | @Pyrexus | New feature / enhancement | feat(feed): Artist Spotlight pager with 5s auto-slide |  |
| [#694](https://github.com/Clash-Projects/LastWave-Native/pull/694) | @Pyrexus | New feature / enhancement | feat(settings): single Lyrics settings entry |  |
| [#651](https://github.com/Clash-Projects/LastWave-Native/pull/651) | @JacksonJones2003 | Fix / refinement | fix(originos): keep lyrics on the Origin Island and match the lyrics screen |  |
| [#695](https://github.com/Clash-Projects/LastWave-Native/pull/695) | @Pyrexus | New feature / enhancement | feat(menu): restore #676 song menu as default, current sheet behind the classic toggle |  |
| [#696](https://github.com/Clash-Projects/LastWave-Native/pull/696) | @Pyrexus | Maintenance / release preparation | fix(build): repair main compile errors from #694/#695 |  |
| [#697](https://github.com/Clash-Projects/LastWave-Native/pull/697) | @Pyrexus | Fix / refinement | fix(lyrics): bold weight for Hindi, Chinese and other non-Latin lyric lines |  |
| [#698](https://github.com/Clash-Projects/LastWave-Native/pull/698) | @Pyrexus | New feature / enhancement | feat(lyrics): letter-level lift animation for word-synced lyrics |  |
| [#699](https://github.com/Clash-Projects/LastWave-Native/pull/699) | @Pyrexus | Reverted / superseded claim | fix(ui): restore the identify-music button beside search | Header recognition shortcut was removed again by #741; recognition remains available through Search. |
| [#701](https://github.com/Clash-Projects/LastWave-Native/pull/701) | @Pyrexus | Fix / refinement | fix(cast): next track hangs on spinner after a song ends |  |
| [#700](https://github.com/Clash-Projects/LastWave-Native/pull/700) | @Pyrexus | Fix / refinement | fix(artist): keep bands with separators in their name as one artist |  |
| [#704](https://github.com/Clash-Projects/LastWave-Native/pull/704) | @Nox-Wizard-py | New feature / enhancement | feat: replace shuffle button with stateful repeat button in media notification (#685) |  |
| [#683](https://github.com/Clash-Projects/LastWave-Native/pull/683) | @Sahank29 | Fix / refinement | fix(player): honour Full Screen Artwork in tablet landscape |  |
| [#705](https://github.com/Clash-Projects/LastWave-Native/pull/705) | @Pyrexus | Fix / refinement | fix(ui): spacing, mini-player art, search covers, mood tiles, AutoMix badge, uniform feed cards |  |
| [#709](https://github.com/Clash-Projects/LastWave-Native/pull/709) | @Pyrexus | Fix / refinement | fix(ui): cut the empty gaps between cards on Stats, Settings and Home |  |
| [#711](https://github.com/Clash-Projects/LastWave-Native/pull/711) | @Pyrexus | New feature / enhancement | Add ListenBrainz and Libre.fm scrobblers |  |
| [#710](https://github.com/Clash-Projects/LastWave-Native/pull/710) | @Sahank29 | Fix / refinement | fix(player): beat sync on every song and at any speed | Final beat-sync refinements; the effect is opt-in and the Lyra shader requires Android 13+. |
| [#716](https://github.com/Clash-Projects/LastWave-Native/pull/716) | @Pyrexus | Fix / refinement | fix(ui): load YouTube user avatar and clean up nested profile placeholder (#708) |  |
| [#717](https://github.com/Clash-Projects/LastWave-Native/pull/717) | @Pyrexus | New feature / enhancement | fix(player): restore progress bar when Liquid Glass disabled; feat(autoeq): import/export and text inputs (#620, #680) | AutoEQ import/export and text inputs remain. The added non-glass mini-player progress bar was removed by #741. |
| [#715](https://github.com/Clash-Projects/LastWave-Native/pull/715) | @Pan-TheMechanic | Fix / refinement | playback: route Dolby Atmos to USB DAC via system spatializer |  |
| [#720](https://github.com/Clash-Projects/LastWave-Native/pull/720) | @Pan-TheMechanic | Fix / refinement | fix(audio): route decoded Dolby Atmos to Exclusive USB on USB DAC and attach kernel driver on close |  |
| [#724](https://github.com/Clash-Projects/LastWave-Native/pull/724) | @NONDEADER | Fix / refinement | fix(player): dismiss keyboard and clear focus when expanding player sheet |  |
| [#706](https://github.com/Clash-Projects/LastWave-Native/pull/706) | @Pyrexus | Major feature | feat(automix): real analysis, beat-locked mixing, DJ EQ and studio popup | AutoMix Pro replaces the older transition approach; final studio shows a transition-window waveform. |
| [#725](https://github.com/Clash-Projects/LastWave-Native/pull/725) | @Sahank29 | Fix / refinement | fix(addons): play from addons without YouTube sign-in |  |
| [#727](https://github.com/Clash-Projects/LastWave-Native/pull/727) | @Sahank29 | Fix / refinement | fix(player): tablet fullscreen lyrics keep the cover art and centre lines |  |
| [#741](https://github.com/Clash-Projects/LastWave-Native/pull/741) | @Nox-Wizard-py | Fix / refinement | fix(ui,recognition): pre-release mini player cleanup, home header layout, and internal audio capture | Internal audio capture and header/mini-player cleanup remain; API 29+ capture needs Android consent. |
| [#742](https://github.com/Clash-Projects/LastWave-Native/pull/742) | @Nox-Wizard-py | Fix / refinement | refactor: remove redundant top-bar video button and toggle from Now Playing top bar |  |

## Integration branch merges

- [Merge branch 'feat/beat-synced-fluid-motion' into main](https://github.com/Clash-Projects/LastWave-Native/commit/2818d42c189f926c2eb6c23fdd256c3b5937a7b0) — @duxtami.
- [Merge branch 'feat/legacy-colors-and-stats-v2' into main](https://github.com/Clash-Projects/LastWave-Native/commit/961835c56632ece634a1179ac274a97439f117c2) — @duxtami.
- [Merge branch 'feat/custom-ui-fixes-and-improvements' into main](https://github.com/Clash-Projects/LastWave-Native/commit/2b1e2b763c29226a9ebfb1fa57dacf412d77e683) — @duxtami.

See FEATURE_AUDIT.md and FIX_AUDIT.md for grouped behaviors and final-source evidence. PR counts are not a count of fixed GitHub issues.
