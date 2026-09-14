# F-Droid custom repo for LastWave

This directory IS a complete F-Droid custom binary repository (fdroidserver
working tree). Users add the repo URL to F-Droid or Droid-ify and get
LastWave + automatic updates. APKs are byte-identical to the upstream
GitHub Releases (same signing certificate) — this repo does not build or
re-sign anything.

## For users — add the repo

Repo URL:

    https://iamsrishanth.github.io/LastWave-Native/fdroid/repo

Repo index signing certificate fingerprint (SHA-256):

    F8 2B CE 41 6A 8F 3F 31 E0 88 58 64 FD D5 CB 4D 6D 8C 92 74 16 F3 35 B1 2B CC 53 AC 74 86 84 17

- **F-Droid client:** Settings → Repositories → `+` → paste the URL → OK.
  Opening the URL in a mobile browser also works (one-tap "Add to F-Droid").
- **Droid-ify:** Settings → Repos → `+` → paste the URL → paste the
  fingerprint above when prompted.
- Versions before 3.3.1 used a different upstream signing key and are not
  served here (install those from GitHub Releases if you really need them).

## For maintainers — how this works

Layout (in the feature/fdroid branch):

- `config.yml` — real repo config (CONTAINS PASSWORDS, gitignored, chmod 600)
- `config.yml.example` — committed, redacted template
- `keystore.p12` — repo index signing key (gitignored, BACK THIS UP — losing
  it means clients can never trust an update from this repo again)
- `metadata/com.lastwave.app.yml` — app metadata
- `repo/` — built output: APKs, index-v1.jar (signed), index-v2.json, icons.
  Gitignored; lives on the GitHub Pages branch, not in feature/fdroid.
- `lastwave_logo.png` — repo icon

### Rebuild the index locally

```bash
cd fdroid
cp /path/to/apks repo/               # new release APK(s), ONE per versionCode
ANDROID_HOME=/usr/lib/android-sdk fdroid update
```

Verify: `jarsigner -verify repo/index-v1.jar`, then serve and test:

```bash
cd .. && python3 -m http.server 8090   # from repo ROOT so URL is /fdroid/repo/
# On device: add http://<lan-ip>:8090/fdroid/repo
```

### Deploy to GitHub Pages

The `repo/` directory content (APKs + index files) goes to the `pages`
branch under `fdroid/`. The CI workflow `.github/workflows/fdroid-repo.yml`
does this automatically on every published release. Manual deploy:

```bash
git worktree add ../lw-pages pages
cp -r fdroid/repo/* ../lw-pages/fdroid/repo/
cd ../lw-pages && git add -A && git commit -m "fdroid: update repo index" && git push origin pages
```

### CI secrets (repo Settings → Secrets → Actions)

- `FDROID_KEYSTORE_BASE64` — `base64 -w0 fdroid/keystore.p12`
- `FDROID_KEYSTORE_PASS` — the keystorepass from config.yml
- `FDROID_KEY_PASS` — the keypass from config.yml

### Hard rules

- NEVER commit `config.yml` or `keystore.p12` to ANY branch. The Pages
  branch is PUBLIC — a leaked config leaks the repo signing key.
- One APK per versionCode: the universal APK only (android7 builds collide
  on versionCode with universal and `fdroid update` rejects duplicates).
- The repo key must never rotate; if it must, it's a new repo for users.

## Local secrets location (this workstation)

`/mnt/data/Projects/personal/LastWave/fdroid/config.yml` + `keystore.p12`.
Back up both to a second location before trusting them.
