# Pillion Release, Signing & In-App Update Architecture

## Overview
Pillion uses Continuous Native Generation (CNG) so `android/` is generated cleanly on every CI build from `app.json`, `plugins/`, and `modules/pillion-native/`.

```mermaid
flowchart LR
  A["Push to main"] --> B["Bump version & versionCode"]
  B --> C["expo prebuild --clean"]
  C --> D["Gradle assembleRelease (arm64-v8a)"]
  D --> E["apksigner verify"]
  E --> F["Commit APK & bump [skip ci]"]
  F --> G["Tag vX.Y.Z & publish GitHub Release"]
```

## Signing Configuration
No GitHub Secrets are required. Release APKs are signed with the committed PKCS#12 keystore (`credentials/release.keystore`, alias `pillion-release`) wired through [withReleaseSigning.js](../plugins/withReleaseSigning.js) and [build-apk.yml](../.github/workflows/build-apk.yml), ensuring every release shares the exact same signing identity for seamless in-place updates.

You can override the keystore location or credentials via `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` environment variables.

## In-App GitHub Releases Self-Updater

```mermaid
sequenceDiagram
  participant App as Pillion App
  participant GH as GitHub Releases API
  participant FS as expo-file-system
  participant OS as Android Package Installer

  App->>GH: GET /repos/husainlab/Bike_Companion_app/releases?per_page=1
  GH-->>App: Release JSON + APK asset URLs
  App->>App: parseRelease() + isNewer() + apkFor(supportedCpuArchitectures)
  App->>FS: File.createDownloadTask(apkUrl, Paths.cache/Pillion-update.apk)
  FS-->>App: Progress 0..1 + contentUri
  App->>OS: ACTION_VIEW (application/vnd.android.package-archive)
  alt Unknown sources disabled
    App->>OS: MANAGE_UNKNOWN_APP_SOURCES (package:com.husainlab.pillion)
  end
```

## Manual Fallback Release Script
If you build an APK locally and want to publish it to GitHub Releases directly:
```bash
GITHUB_TOKEN=ghp_... ./scripts/github-release.sh 1.0.1 ./Pillion-1.0.1.apk
```

