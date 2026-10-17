# Builds

- **Release Artifact:** `LastWave-v4.3.0-release.apk` (**22.7 MB**)
  - Variant: `release` (`:app:assembleRelease`)
  - Optimizations: R8 code shrinking, dead-code elimination, resource shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`)
- **Debug Artifact:** `LastWave-v4.3.0-debug.apk` (**120.4 MB**)
  - Variant: `debug` (`:app:assembleDebug`)
  - Uncompressed full DEX with all debug symbols, no R8 shrinking
- **Version:** `4.3.0` (versionCode `25`)
- **Feature:** Full Screen Cover Art implementation (static & animated canvas full-bleed hero with dynamic bottom fade and scrim protection)
- **Toolchain:** Gradle 9.3.1, OpenJDK 17, Android compileSdk 37, targetSdk 35, minSdk 29
