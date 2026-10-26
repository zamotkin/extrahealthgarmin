---
type: overview
title: Extra Health Garmin — project overview
status: current
generated: 2026-10-26
tags: [android, health-connect, garmin, kotlin]
---
Extra Health Garmin (v3.0.0) is a single-module Android app (Kotlin, minSdk 26,
targetSdk 34, compileSdk 36) that moves personal health data into Garmin Connect.

**Pipelines:**
1. **Weigh-ins (Health Connect → Garmin).** `health/` reads seven HC record
   types (weight, body fat, bone mass, lean body mass, body water, BMR,
   height), correlates same-time measurements into one weigh-in
   (`BodyMeasurementAggregator`), dedups and uploads as a FIT weight_scale
   file. Foreground "Sync now" via `HealthSyncFacade`; background via the
   WorkManager `SyncWorker` (~3 h, network-constrained).
2. **Blood pressure (pluggable sources → Room → Garmin).** `devices/` defines
   the vendor-neutral `BpSource` seam; three sources ship: the Omron M7
   Intelli IT proprietary BLE stack, the standard BLE Blood Pressure Service
   0x1810, and Health Connect (singleton). `SourceReadUseCase` reads into the
   Room store in `omron/data/` (idempotent by a unique
   (timestamp, userSlot, sourceId) index); `BpSyncCoordinator` uploads a
   user slot as one `blood_pressure.fit` batch. No background BP work —
   reads and uploads run in the foreground from the Measurements tab.
3. **Hydration (Health Connect → Garmin).** `HydrationSync` PUTs new HC
   hydration entries to Garmin's daily log, remembering sent entries (35 d)
   and compensating deletions/edits with negative logs; no backfill.

**App shape.** Single activity, no fragments, no XML layouts: the whole UI is
Jetpack Compose under the shared `EhgTheme` — `MainActivity.setContent` →
`ui/navigation/AppRoot` (Scaffold + bottom nav Home / Measurements / Settings;
Measurements renders only while at least one BP source is connected, epic R8).
MVVM: ViewModels own logic (LiveData bridged via observeAsState), Composables
render and forward taps. Manual wiring, no DI framework
(`HealthSyncWiring`, `*ViewModelFactory`).

**Key state.** Weigh-in sync state is one JSON file (`hc-sync-state.json`);
connected BP sources live in `bp-sources.json`; BP rows in the only Room
database (`blood_pressure.db`). Garmin DI OAuth2 tokens and the Omron pairing
key are AES-encrypted in the Android Keystore.

Deeper detail: ADRs in `adr/`, entities in `entities/`, and the repo map in
`CLAUDE.md` at the project root.
