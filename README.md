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


## Features

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback
- Stream any song or video from YouTube Music
- Background playback
- Download & cache for offline use
- Skip silence
- Sleep timer

</td>
    <td width="50%" valign="top">

#### Audio
- Bit-Perfect USB DAC output & Lossless FLAC addon support
- Audio normalization
- Tempo & pitch control
- 15-band Parametric Equalizer & AutoEQ presets
- Smart Automix & Crossfade transitions

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Lyrics & Discovery
- Live synced lyrics
- AI-powered lyrics translation
- Personalized quick picks
- Search songs, albums, artists, videos, and playlists

</td>
    <td width="50%" valign="top">

#### Library & Account
- Full library management
- Local playlists
- Import playlists (Spotify & Apple Music)
- Reorder songs in playlist or queue
- YouTube Music account login
- Sync songs, artists, albums, and playlists

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Social
- Listen together with friends in real-time
- Last.fm integration for scrobbling
- Safe Discord Rich Presence

</td>
    <td width="50%" valign="top">

#### Interface
- Home screen widget
- Light / Dark / Black / Dynamic theme modes
- Dynamic color + 19 preset color palettes
- Built with Material 3 Expressive & Liquid Glass UI

</td>
  </tr>
</table>

---



## Getting Started

### Installation

| Channel | Source | Compatibility |
|:---|:---|:---|
| **Stable Release** | [GitHub Releases](https://github.com/Clash-Projects/LastWave-native/releases) | Recommended for all users • Android 7.0+ (API 24+) |
| **Nightly Builds** | [GitHub Actions](https://github.com/Clash-Projects/LastWave-native/actions) | Latest automated builds with bleeding-edge features |

### Quick Setup

1. Install the APK on your Android device.
2. *(Optional)* Navigate to **Settings → Audio** to configure:
   - **Bit-Perfect Output:** Enable exclusive USB DAC mode for bit-perfect audio.
   - **AutoEQ Presets:** Search for your headphones/IEMs to apply calibrated parametric EQ curves.
   - **Lossless Addons:** Enable the modular addon service for 24-bit Hi-Res FLAC playback.
3. Start streaming ad-free music, listening to customized radios, and enjoying synced lyrics!

---

## Building from Source

### Prerequisites
- **JDK:** OpenJDK 17 or higher
- **Android SDK:** API Level 34+ with CMake & Android NDK installed (for native audio C++ engine & USB driver)

### Build Instructions

```bash
# Clone the repository
git clone https://github.com/Clash-Projects/LastWave-native.git
cd LastWave-native

# Build the release APK
./gradlew assembleRelease

# The generated APK will be at:
# app/build/outputs/apk/release/app-release-unsigned.apk
```

> [!TIP]
> For local development and debugging, run `./gradlew assembleDebug` or install directly to a connected device via `./gradlew installDebug`.

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


## Contributors

LastWave wouldn't be possible without our incredible community of contributors. Thank you for making it better every day!

<p align="center">
  <a href="https://github.com/Clash-Projects/LastWave-Native/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=Clash-Projects/LastWave-Native" alt="Contributors" />
  </a>
</p>

---


## Disclaimer

> [!NOTE]
> **Educational & Research Notice**
>
> LastWave is an open-source, non-commercial application developed strictly for research and educational purposes to demonstrate modern Android application architecture with Jetpack Compose and AndroidX Media3.
>
> **No Affiliation & Content Policy**
> - LastWave is an independent project and is **not affiliated with, endorsed, or sponsored by Google LLC, YouTube, YouTube Music, Spotify, Apple Inc., Last.fm, or any music service**.
> - LastWave **does not host, stream from private servers, or distribute any copyrighted media**. All content and metadata are accessed directly from public endpoints in accordance with their respective terms. All trademarks belong to their respective owners.

<!-- [![GitGem](https://gitgem.org/api/badge/github/Clash-Projects/LastWave-Native.svg)](https://gitgem.org/github/Clash-Projects/LastWave-Native) -->
<a href="https://gitgem.org/github/Clash-Projects/LastWave-Native"><img src="https://gitgem.org/api/badge/github/Clash-Projects/LastWave-Native.svg" width="0" height="0" alt="" /></a>
