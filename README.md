<div align="center">

<img src="LastWave_banner_v2_1440p.png" alt="LastWave Banner" width="100%" style="border-radius: 16px;" />

<h1>LastWave</h1>

**Simple Android YouTube Music client with Material UI, Liquid Glass, Bit-Perfect playback, Automix, Lossless addons & advanced audiophile features.**

<br/>

<p align="center">
  <a href="https://t.me/clashprojects" target="_blank"><img src="docs/badges/btn_telegram_support.svg" alt="Telegram Support" height="42" /></a>
  <a href="https://t.me/MaterialYouApp" target="_blank"><img src="docs/badges/btn_telegram_channel.svg" alt="Telegram Channel" height="42" /></a>
  <a href="https://discord.gg/DmyM2p2fMe" target="_blank"><img src="docs/badges/btn_discord.svg" alt="Discord Community" height="42" /></a>
</p>

<p align="center">
  <a href="https://trendshift.io/repositories/197973?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-197973" target="_blank" rel="noopener noreferrer">
    <img src="https://trendshift.io/api/badge/trendshift/repositories/197973/daily?language=Kotlin" alt="Clash-Projects%2FLastWave-Native | Trendshift" width="250" height="55" />
  </a>
  &nbsp;
  <a href="https://trendshift.io/repositories/197973?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-197973" target="_blank" rel="noopener noreferrer">
    <img src="https://trendshift.io/api/badge/trendshift/repositories/197973/weekly?language=Kotlin" alt="Clash-Projects%2FLastWave-Native | Trendshift" width="250" height="55" />
  </a>
</p>
</div>

---




## Overview

LastWave is a fast, native YouTube Music client for Android that looks great and sounds even better. Built with Material 3 Expressive and Liquid Glass UI, it gives you a clean, fluid design that adapts to your music.

For audio lovers, LastWave packs Bit-Perfect USB DAC output, Lossless FLAC addon support, AutoEQ headphone presets with parametric EQ, smooth Automix transitions, and real-time synced lyrics, all in a lightweight app.

---


## <img src="https://api.iconify.design/lucide/layers.svg?color=%23C6F100" width="20" height="20" align="center" /> Key Features

| Icon | Feature | Highlight |
|:---:|:---|:---|
| <img src="https://api.iconify.design/lucide/play-circle.svg?color=%23FF0000" width="20" height="20" /> | **YouTube Music Client** | Stream tracks, albums, artists, and public playlists from YouTube Music with zero ads and background playback. |
| <img src="https://api.iconify.design/lucide/wand-2.svg?color=%23C6F100" width="20" height="20" /> | **Smart Playlist Generator** | Algorithmic taste mixes and mood radios generated from your listening history, seed artists, loved tracks, and top genres. |
| <img src="https://api.iconify.design/lucide/headphones.svg?color=%23C6F100" width="20" height="20" /> | **Bit-Perfect & AutoEQ** | Audiophile playback engine bypassing OS resampling, integrated AutoEQ profile database, and a 15-band parametric equalizer. |
| <img src="https://api.iconify.design/lucide/mic.svg?color=%23FFB4A2" width="20" height="20" /> | **Real-Time Synced Lyrics** | Millisecond-accurate animated karaoke lyrics powered by LRCLIB with 8 customizable fluid physics motions. |
| <img src="https://api.iconify.design/lucide/arrow-down-to-line.svg?color=%23C6F100" width="20" height="20" /> | **Offline Downloader** | One-tap downloads saved directly to local storage (`Music/LastWave`), fully tagged with high-res cover art and synchronized `.lrc` lyrics. |
| <img src="https://api.iconify.design/lucide/compass.svg?color=%2300E5FF" width="20" height="20" /> | **Discovery Feed & Genre DNA** | Personalized recommendation radar with deep genre breakdowns, weekly listening recaps, and instant "Start Mix" radios. |
| <img src="https://api.iconify.design/lucide/share-2.svg?color=%23C6F100" width="20" height="20" /> | **Cross-Platform Playlist Import** | Instantly import public playlists from Spotify and Apple Music directly into your LastWave library. |
| <img src="https://api.iconify.design/lucide/disc.svg?color=%2300E5FF" width="20" height="20" align="center" /> | **Lossless Addons & Automix** | Modular addon service for 24-bit Hi-Res FLAC streaming/downloads alongside DJ-style smart Automix crossfading. |
| <img src="https://api.iconify.design/lucide/sparkles.svg?color=%23C6F100" width="20" height="20" /> | **Material 3 & Liquid Glass** | Real-time backdrop refraction shaders, dynamic monet theming, fluid card physics, and tactile haptics. |

---



## <img src="https://api.iconify.design/lucide/cpu.svg?color=%23C6F100" width="20" height="20" align="center" /> Tech Stack & Architecture

- **Language & UI:** 100% Kotlin + Jetpack Compose (Material 3 Expressive)
- **Audio Engine:** AndroidX Media3 ExoPlayer with lockscreen media controls & Android Auto integration
- **Streaming Catalog:** YouTube Music streaming engine with high-efficiency Opus audio
- **Audiophile Pipeline:** Bit-Perfect playback engine, AutoEQ database integration & modular Lossless Addon Service
- **Lyrics Engine:** [LRCLIB](https://lrclib.net) millisecond-synchronized lyrics
- **Architecture:** MVVM + Clean Architecture, Room DB, Jetpack DataStore, Dagger Hilt, Coroutines & Flow

---


## <img src="https://api.iconify.design/lucide/rocket.svg?color=%23C6F100" width="20" height="20" align="center" /> Getting Started

1. Download the latest APK from the **[Releases](https://github.com/Clash-Projects/LastWave-native/releases)** or **[Actions](https://github.com/Clash-Projects/LastWave-native/actions)** tab.
2. Install `LastWave-v5.0.0-release.apk` on your Android device (Android 7.0+).
3. Configure your audio pipeline, AutoEQ headphone profile, and Lossless Addons in **Settings → Audio**.
4. Enjoy ad-free streaming, bit-perfect playback, and intelligent Automix radios!

---


## <img src="https://api.iconify.design/lucide/terminal.svg?color=%23C6F100" width="20" height="20" align="center" /> Building from Source

```bash
git clone https://github.com/Clash-Projects/LastWave-native.git
cd LastWave-native
./gradlew assembleRelease
```

---

## Support the Project

If LastWave has been useful to you, consider supporting its development.

<div align="left">
  <table style="margin: 0 auto; border-collapse: collapse; border: none;">
    <tr>
      <td align="center" style="padding: 15px; border: none;">
        <a href="https://buymeacoffee.com/ajisth" target="_blank" style="text-decoration:none;"><img src="docs/badges/bmac.png" alt="Buy Me A Coffee" width="140" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/></a>
      </td>
      <td align="center" style="padding: 15px; border: none;">
        <a href="https://buymeachai.ezee.li/ajisth69" target="_blank" style="text-decoration:none;"><img src="docs/badges/upi.png" alt="UPI (Buy Me A Chai)" width="140" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/></a>
      </td>
      <td align="center" style="padding: 15px; border: none;">
        <a href="https://github.com/sponsors/ajisth69" target="_blank" style="text-decoration:none;"><img src="docs/badges/github_sponsors.svg" alt="GitHub Sponsors" width="140" style="border-radius: 12px; box-shadow: 0 8px 16px rgba(0,0,0,0.2);"/></a>
      </td>
    </tr>
  </table>
</div>



---


## <img src="https://api.iconify.design/lucide/message-circle.svg?color=%2324A1DE" width="20" height="20" align="center" /> Community & Support

* <img src="https://api.iconify.design/lucide/send.svg?color=%2324A1DE" width="16" height="16" align="center" /> **Updates & Support:** [Join @clashprojects on Telegram](https://t.me/clashprojects)

* <img src="https://api.iconify.design/lucide/sparkles.svg?color=%230088cc" width="16" height="16" align="center" /> **More From Us:** [Join @MaterialYouApp on Telegram](https://t.me/MaterialYouApp)

* <img src="https://api.iconify.design/lucide/message-square.svg?color=%235865F2" width="16" height="16" align="center" /> **Discord Community:** [Join Discord](https://discord.gg/DmyM2p2fMe)

* <img src="https://api.iconify.design/lucide/globe.svg?color=%2324A1DE" width="16" height="16" align="center" /> **LastWave Website:** [visit site now](https://lastwave.pages.dev)

---


## <img src="https://api.iconify.design/lucide/shield-alert.svg?color=%23FFB4A2" width="20" height="20" align="center" /> Disclaimer

> [!NOTE]
> **Educational & Research Notice**
>
> LastWave is an open-source, non-commercial application developed strictly for research and educational purposes to demonstrate modern Android application architecture with Jetpack Compose and AndroidX Media3.
>
> **No Affiliation & Content Policy**
> - LastWave is an independent project and is **not affiliated with, endorsed, or sponsored by Google LLC, YouTube, YouTube Music, Spotify, Apple Inc., Last.fm, or any music service**.
> - LastWave **does not host, stream from private servers, or distribute any copyrighted media**. All content and metadata are accessed directly from public endpoints in accordance with their respective terms. All trademarks belong to their respective owners.

---

[![GitGem](https://gitgem.org/api/badge/github/Clash-Projects/LastWave-Native.svg)](https://gitgem.org/github/Clash-Projects/LastWave-Native)
