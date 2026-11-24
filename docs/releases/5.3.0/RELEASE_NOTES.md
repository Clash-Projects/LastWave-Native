### ✨ What's New

* **AutoMix Pro:** Audio analysis, beat alignment, bass exchange and lyric-aware cues for compatible transitions, plus a live studio popup with both tracks and transition waveforms.
* **Player Lyrics & Signature Card:** Synced lyrics inside Now Playing, saved **Auto / Center** alignment, and LastWave's signature card for intros, loading and unavailable lyrics.
* **Flow, Halo & Letter Motion:** Two new lyrics styles with an in-player chooser, plus smoother letter-by-letter lift for default word-synced lyrics, preserving connected scripts.
* **Lyra Beat-Synced Artwork:** Optional fluid artwork motion driven by audible playback, refined across songs and playback speeds. **Android 13+; off by default.**
* **ListenBrainz & Libre.fm:** Two additional scrobblers with independent connections and compatible-server support alongside Last.fm.
* **Listening Insights & Legacy Colours:** More listening charts, records and comparisons, a collapsing Stats header, and an option for the earlier dynamic dark palette.
* **Waveform & Playback Controls:** A new seekbar style with interactive previews, optional pause/resume at zero media volume, and Repeat controls in the notification.
* **Headphone Music Recognition:** Recognize permitted audio from other apps through wired or Bluetooth headphones. **Android 10+; capture consent required.**
* **AutoEQ Import & Export:** Exchange EQ configurations and enter frequency, gain and Q values directly.
* **Home & Library Controls:** Auto-sliding Artist Spotlight, hideable Quick access, and connected **Local songs / Downloads** pills above playlists.

---

### ⚡ Improved

* **Startup & Performance:** Less launch work, background redraws, repeated settings processing and audio allocation, with smoother player and library loading.
* **Home Feed:** Earlier useful content, more stable refresh and pagination, and consistent card spacing and artwork proportions.
* **Menus & Localization:** Refined song menus, haptics and lyrics controls, with completed Spanish translations.

---

### 🛠️ Fixed

* **Queue & Cast:** Corrected drag reordering, playlist-end repeat behavior and repeated shuffle order; fixed Cast hanging on the next song.
* **Lyrics & Tablets:** Cleared stale lyrics on track changes, improved non-Latin bold text and clipping, removed duplicate tablet controls and retained artwork beside fullscreen lyrics.
* **Recommendations & Library:** Better Spotify recording matches, respected recommendation exclusions, corrected recognized band names, local artwork and newest-first likes.
* **Addons & USB Audio:** Restored addon playback without YouTube sign-in and improved decoded Atmos routing and USB driver cleanup.
* **Player, Accounts & OriginOS:** Fixed profile artwork, keyboard/focus dismissal, video controls, Android 14+ recognition capture and Island/widget lyric synchronization.
* **Backups & Diagnostics:** Preserved more audio settings and configuration during restore and cleaned up old exported reports.

> [!NOTE]
> AutoMix Pro and Pause at Zero Volume are optional and off by default. AutoMix uses musical matching when the tracks and output support it. The Waveform seekbar is a progress animation; the studio waveforms show the transition window.

---

### 🙌 Contributors

Thanks to **[Ayush](https://github.com/Pyrexus)**, **[Dux Tami](https://github.com/duxtami)**, **[Sahan](https://github.com/Sahank29)** and **[Shoyo](https://github.com/Nox-Wizard-py)** for the main feature and refinement work, and **[Pan-TheMechanic](https://github.com/Pan-TheMechanic)**, **[JacksonJones2003](https://github.com/JacksonJones2003)**, **[NONDEADER](https://github.com/NONDEADER)**, **[PedroFlores199](https://github.com/PedroFlores199)**, **[Simone20031](https://github.com/Simone20031)** and **[xppenTC](https://github.com/xppenTC)** for their merged contributions.

---

### 📦 Download Packages & Compatibility

| Package | Recommended OS | Description |
| :--- | :--- | :--- |
| **`LastWave-v5.3.0-universal.apk`** (~38 MB) | **Android 10+** | **Standard recommended release.** Optimized APK. |
| **`LastWave-v5.3.0-universal-uncompressed.apk`** (~112 MB) | **Android 10+** | **Raw release.** Without code minification or resource shrinking. |
| **`LastWave-v5.3.0-android7.apk`** (~29 MB) | **Android 7.0–9.0** | **Legacy variant.** Lower minimum Android version. |
| **`LastWave-v5.3.0-vivo-origin.apk`** (~38 MB) | **vivo / OriginOS, Android 10+** | **Origin Island and music-widget variant.** Separate app package. |

> [!NOTE]
> **vivo variant:** Uses the allowlisted `com.tencent.qqmusic` package. It installs separately from standard LastWave, does not share its data, and cannot coexist with QQ Music. Update it using the vivo APK. Uninstall an older vivo variant shown as Spotify before installing this package.

> [!NOTE]
> **Older Android:** Hardware blur requires Android 12+; the Signature Liquid Glass navigation selector and Lyra effect require Android 13+. Earlier versions retain compatible fallback visuals. Android 10+ users should choose the standard universal APK.
