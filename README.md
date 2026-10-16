# Extra Health Garmin — Health Connect to Garmin Connect body-composition sync

<p align="center"><img src="docs/images/social-preview.png" alt="Extra Health Garmin" width="640"></p>

[![GitHub release](https://img.shields.io/github/v/release/zamotkin/extrahealthgarmin)](https://github.com/zamotkin/extrahealthgarmin/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/zamotkin/extrahealthgarmin/total)](https://github.com/zamotkin/extrahealthgarmin/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![SDK](https://img.shields.io/badge/minSdk%2026%20%C2%B7%20targetSdk%2034-blue.svg)](app/build.gradle)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)

**Extra Health Garmin** syncs body-composition weigh-ins from **Health Connect** into **Garmin Connect** — automatically, in the background, with no account data ever stored beyond encrypted session tokens. Each weigh-in is encoded as a standard FIT weight-scale file with the official Garmin FIT SDK and uploaded straight to your Garmin Connect account: no manual CSV export, no middleman server, no analytics.

Open source (MIT), no ads, no tracking — just your scale data where you want it.

## 📱 Features — Health Connect → Garmin body-composition sync

- **🔄 Health Connect source**: reads weight, body fat, bone mass, lean body mass, body water, basal metabolic rate and height measurements from any scale or app that writes into Health Connect
- **🧩 Smart aggregation**: measurements taken at the same time are combined into one weigh-in (weight + fat + BMI, with the height record cached for BMI)
- **⬆️ Garmin auto-upload**: new weigh-ins are encoded into a FIT file (official Garmin FIT SDK) and uploaded straight into your Garmin Connect account — no manual import, no CSV files
- **🔐 One-time login**: Garmin sign-in (email + password + MFA verification code, garth-style DI OAuth2) — only Keystore-encrypted tokens are stored, never your credentials
- **⏱️ Background sync**: a WorkManager job re-syncs roughly every 3 hours when a network connection exists; a manual **Sync now** button is always available
- **✅ Dedup journal**: record ids of uploaded weigh-ins are journaled on-device, so the same weigh-in is never uploaded twice — even across reinstalls of the sync flow or re-reads after a token expiry
- **🔁 Offline retry**: failed uploads stay retriable and ride along with the next sync pass (exponential backoff on network problems)
- **🌍 Multilingual**: full English and Russian UI

## 📸 Screenshots

**Main screen** — Health Connect permission status, one-tap sync and Garmin login state at a glance:

<img src="docs/images/screenshot-main.png" width="320" alt="Extra Health Garmin main screen — Health Connect to Garmin Connect sync status with Sync now button">

**After a sync pass** — the dedup journal in action: everything already uploaded to Garmin Connect, nothing re-sent:

<img src="docs/images/screenshot-sync-result.png" width="320" alt="Extra Health Garmin after sync — all measurements already in Garmin Connect, nothing to upload (dedup journal)">

**Demo** — a full manual sync pass, from tap to result (opens the video in the browser player):

<a href="docs/images/demo-sync.mp4">
    <img src="docs/images/screenshot-main.png" alt="Demo: one manual sync pass from tap to result" width="320">
</a>

## 🚀 Quick Start — install, link Garmin Connect, sync

### Installation

1. Download [`app-release.apk`](https://github.com/zamotkin/extrahealthgarmin/releases/latest/download/app-release.apk) from the [latest release](https://github.com/zamotkin/extrahealthgarmin/releases/latest)
2. Enable "Install from unknown sources" in Android settings
3. Install the APK (Android 8.0+ / API 26+)

On first sync the app backfills weigh-ins from the last 30 days; after that it picks up only new Health Connect records.

### Usage

1. **Grant permissions** — on first launch tap the Health Connect status line to install/open Health Connect and grant the read permissions
2. **Connect Garmin** — sign in once with your Garmin email, password and (if enabled) MFA code; tokens are refreshed automatically afterwards
3. **Sync** — tap **Sync now**, or just let the ~3-hourly background sync do it: every new weigh-in lands in Garmin Connect on its own

## ⚠️ Limitations

- **Unofficial API** — the upload uses Garmin's DI endpoint (the wire contract of [garth](https://github.com/matin/garth) / python-garminconnect). Garmin can change or block it at any time.
- **garmin.com only** — Garmin Connect China (garmin.cn) accounts are not supported.
- **Background reads** — on some providers the Health Connect background-read permission cannot be granted; the periodic sync then quietly does nothing and manual foreground sync keeps working.

## 🔒 Privacy & Security

- The app **only reads** Health Connect data — it never adds, changes or deletes anything there.
- **Nothing is sent anywhere except Garmin Connect.** No analytics, no telemetry, no third parties.
- No measurement values are stored by the app — only record identifiers (for dedup) and sync bookkeeping (a changes token and a timestamp), in app-private storage.
- The Garmin email and password exist only in memory during the login dialog and are never written to disk. The only persisted credentials are DI OAuth2 tokens, AES-encrypted with an Android Keystore key.
- **Debug builds** log the HTTP exchanges of the Garmin traffic with every secret redacted (`Authorization` headers, password, MFA code, tokens → `<redacted>`); **release builds** log none of it.

## 🏗️ Architecture

```
MVVM, single activity
├── ui/MainViewModel            — LiveData state for the home screen
├── MainActivity                — Health Connect status, login dialog, sync UI
├── health/
│   ├── HealthConnectSource     — record reads + changes tokens
│   ├── BodyMeasurementAggregator — correlates measurements into weigh-ins
│   ├── SyncEngine              — one sync pass: fetch → cache height → aggregate → dedup → upload
│   ├── SyncWorker              — WorkManager ~3h periodic pass (same engine)
│   └── SyncStateStore          — sync state JSON in filesDir
├── data/GarminRepository       — upload orchestration gateway
└── garmin/                     — DI OAuth2 auth + token store (Keystore), FIT builder,
                                  upload client, dedup journal, redacting logger
```

State is deliberately a single JSON file in `filesDir` (no Room, no DataStore): the Health Connect changes token, the last-sync instant, the processed-record-id dedup set and the cached height.

## 🧰 Tech Stack

- **Kotlin** on Android (minSdk 26 / targetSdk 34, compileSdk 36), single-module Gradle build
- **Health Connect API** — reads seven body-composition record types via changes tokens
- **Official Garmin FIT SDK** — encodes weigh-ins into a `weight_scale` FIT file
- **WorkManager** — network-constrained ~3-hourly periodic sync with exponential backoff
- **Android Keystore (AES)** — DI OAuth2 tokens encrypted at rest
- MVVM + LiveData, manual wiring, no DI framework — see [CLAUDE.md](CLAUDE.md) for the full architecture notes

## 🔧 Development

### Prerequisites
- Android Studio (Ladybug or later recommended)
- JDK 17+
- Android SDK API 36

### Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build (needs local app/release.keystore — gitignored)
./gradlew assembleRelease

# Run tests (152 unit tests)
./gradlew testDebugUnitTest

# Lint check
./gradlew lintDebug

# Build + install + launch on a connected device
./debug_setup.sh

# Runtime logs
adb logcat -s MainActivity MainViewModel SyncWorker
```

### Debug Garmin Auth Spike (debug builds only)

Debug builds contain a hidden trigger that runs one live Garmin DI OAuth2 login and reports the result to logcat (release builds have no such trigger). Put `GARMINSPIKE_EMAIL` / `GARMINSPIKE_PASSWORD` / `GARMINSPIKE_MFA_CODE` into `~/.gradle/gradle.properties`, build, then **long-press the status text** on the main screen and watch `adb logcat -s MainActivity GarminAuth GarminHttp`. Remove the properties when done.

## 📚 Documentation

- [Release Notes](RELEASE_NOTES.md) — version history
- [docs/](docs/) — historical design and setup notes

## 🤝 Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for build, test and pull-request conventions.

## 📄 License

This project is licensed under the [MIT License](LICENSE) — free to use, modify and redistribute.

---

If Extra Health Garmin saves you a manual CSV import, consider **starring the repo** — it helps other Health Connect users find it. 🌟
