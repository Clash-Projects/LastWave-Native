# LastWave 5.3.0 draft release package

Version **5.3.0 / code 34**, prepared from main `75e6bdcb` after published **5.2.1**. The release notes and analysis cover merged pull requests and integration branches.

- `RELEASE_NOTES.md`: concise GitHub release body, with separate features, improvements, fixes and contributor credits.
- `TELEGRAM_POST.txt`: matching channel-post draft. Its release link becomes public after publishing.
- `RELEASE_AUDIT.md`: report of the main features and fixes.
- `FEATURE_AUDIT.md`, `FIX_AUDIT.md`, `MERGED_CHANGES.md`, `CREDITS.md`, `release-audit.json`: supporting evidence and attribution.
- `assets/`: four signed APK variants and the exact build `SHA256SUMS.txt`.
- `RELEASE_MANIFEST.json`, `TEST_RESULTS.json`: artifact identities/checks and the 197 passing regression tests.

Build: https://github.com/Clash-Projects/LastWave-Native/actions/runs/45432047472

Preparation PR: https://github.com/Clash-Projects/LastWave-Native/pull/743

All four APKs passed package/version/minimum-SDK, previous-release certificate, SHA256, archive integrity and 16 KB native ZIP alignment checks. All 197 regression tests passed. No device playback or live-service verification was performed.

The preparation PR remains a draft and has not been merged. The release will remain unpublished until the user approves it. The successful release-build run is the validation evidence; no automatic PR checks are claimed to have passed.
