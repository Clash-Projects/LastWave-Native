# LastWave Desktop

A desktop companion application for LastWave, built with **Kotlin Multiplatform** and **Compose for Desktop**.

## Overview

This project provides a desktop version of the LastWave music player, featuring:
- Hi-Res Lossless FLAC playback interface
- Material 3 Expressive design matching the Android app
- Now playing with album art, controls, and seek bar
- Album browsing and favorites
- Track search functionality

## Project Structure

```
desktop/
├── build.gradle.kts       - Gradle build configuration
├── src/
│   └── main/
│       ├── kotlin/
│   │   └── com.lastwave.desktop/
│   │       ├── DesktopEntryPoint.kt   - Application entry point
│   │       ├── di/                     - Dependency injection graph
│   │       ├── data/                   - Data layer (API, models, repos)
│   │       ├── ui/                     - Compose for Desktop UI
│   │       │   ├── main/MainScreen.kt  - Main music player screen
│   │       │   ├── player/             - Player controls
│   │       │   ├── theme/              - LastWaveDesktopTheme
│   │       │   └── util/               - Utility functions
│   │       └── repository/             - Repository interfaces and impls
│   └── res/                            - Desktop resources
├── settings.gradle.kts                 - Root project includes :desktop
```

## Building and Running

### Prerequisites

- JDK 21 or newer
- Gradle 8.4+
- Kotlin 1.9.23+

### Run via Gradle

```bash
cd /home/user/LastWave-XML
./gradlew :desktop:run
```

Or using the Gradle wrapper from the project root.

### IDE Setup

Open the project in **Android Studio** or **IntelliJ IDEA**:
- The desktop module is a standard JVM/Kotlin/Compose module
- Android Studio provides first-class Compose for Desktop support
- Ensure the "Compose for Desktop" plugin is enabled

### Manual Execution

```bash
# Compile and run the desktop application
cd /home/user/LastWave-XML
./gradlew :desktop:run
```

## Design

The UI follows the same **Material 3 Expressive** design language as the Android LastWave app:
- Dynamic color palette with pink/cyan accents (#FFBB86FC, #03DAC6)
- Dark theme default (matching the Android app's aesthetic)
- Rounded corners, fluid morphing cards, and tactile visual feedback
- Consistent typography and spacing scales

## Data Layer

The project includes a stub data layer that mirrors the Android app's architecture:
- `LastWaveApi` - Interface for network calls (streaming, downloads, scrobbling)
- `LastWaveApiClient` - Mock implementation for demonstration
- `AlbumRepository` / `AlbumRepositoryImpl` - Data source for albums/favorites
- `Track` / `Album` - Data models with serialization support

## Extending

To add more features:

1. **New UI screens**: Add composable functions under `ui/`
2. **New data sources**: Extend the repository layer and API client
3. **Playback integration**: Connect to the media player service or ExoPlayer desktop port
4. **Backend API**: Replace the mock client with real API calls to your streaming backend

## Screens

- **MainScreen**: Displays now playing track, album art, playback controls, progress seek bar, and quick access to library/discovery sections
- **PlayerControls**: Compact playback controls with album art, play/pause, skip buttons, and duration display