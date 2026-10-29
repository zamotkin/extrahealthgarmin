---
type: entity
title: health/ — the Health Connect sync core
status: current
generated: 2026-10-26
tags: [health-connect, sync]
---
**Responsibility.** Package `health/`: the weigh-in pipeline (HC → Garmin),
its state, background pass, alerts, and the optional HC blood-pressure and
hydration pipelines.

**Core classes.**
- `HealthConnectSource` — reads the seven record types; manages changes
  tokens (first run = 30-day backfill; expired token = range re-read since
  the last sync anchor). `HealthPermissions` pins the permission set.
- `BodyMeasurementAggregator` — correlates same-time measurements into one
  weigh-in (anchor + companions; newest height cached so BMI survives).
- `SyncEngine` — one pass: fetch → cache height → aggregate → dedup (against
  `SyncState.processedRecordIds`) → upload, mapping onto `SyncResult`
  (Idle/Uploaded/PartialUploaded/Retryable/LoginRequired).
- `SyncStateStore` — one JSON file `filesDir/hc-sync-state.json` (changes
  token, last-sync instant, processed-record-id set, cached height); atomic
  temp+rename writes. No DataStore anywhere in the app.
- `SyncWorker` — WorkManager ~3-hourly periodic pass over the same engine
  (network-constrained, exponential backoff; only Retryable asks for a retry)
  — the only background job in the app (ADR-07).
- `SyncAlerts` — once-per-episode notifications for problems the user must
  fix (sign-in gone, HC revoked, records rejected, a day of failures,
  unused-app pause, battery optimization).
- `BloodPressureSync` — optional READ_BLOOD_PRESSURE, own changes token +
  `blood-pressure-sync.json`; uploads HC blood pressure as a FIT
  blood_pressure file (`GarminBloodPressureRepository`,
  `FitBloodPressureFileBuilder`); Garmin needs a pulse, so readings wait for
  the watch's heart rate at that minute (`dailyHeartRate`), max 24 h. Wrapped
  as a source by `HcBpSource` (CURRENT-only).
- `HydrationSync` — optional READ_HYDRATION, `hydration-sync.json` +
  `GarminHydrationRepository`; PUTs new entries to Garmin's daily log,
  remembers sent entries (35 d), compensates deletions/edits with negative
  logs; no backfill (Garmin cannot tell what is already counted).

**Tests.** `SyncEngineTest`, `SyncStateStoreTest`, `SyncWorkerTest`,
`BodyMeasurementAggregatorTest`, `BloodPressureSyncTest`, `HydrationSyncTest`,
`SyncAlertsTest`, `HealthPermissionsTest`.
