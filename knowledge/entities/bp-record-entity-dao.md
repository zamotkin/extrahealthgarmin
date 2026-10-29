---
type: entity
title: BloodPressureRecordEntity + DAO — the BP Room store
status: current
generated: 2026-10-26
tags: [room, persistence]
---
**Responsibility.** The only Room database in the app (schema v2,
`blood_pressure.db`, exportSchema=false, destructive fallback — ADR-04).
Files: `omron/data/BloodPressureRecordEntity.kt`, `BloodPressureDao.kt`,
`BloodPressureDatabase.kt`.

**Row (`BloodPressureRecordEntity`).** `timestampEpochSec`, `systolic`,
`diastolic`, `pulse`, `mov`, `ihb`, `userSlot` (1|2 for Omron; 0 =
APP_OWNER_SLOT), `sourceId` (ConnectedSource UUID; legacy rows default
`LEGACY_SOURCE_ID = "omron"`), `synced`. **Unique index on (timestampEpochSec,
userSlot, sourceId)** — the dedup key making device re-reads idempotent; the
same measurement via two sources is two rows. The database file is excluded
from cloud backup (backup_rules/data_extraction_rules).

**DAO (`BloodPressureDao`).** `insertIgnoreAll` (returns row ids; -1 =
ignored duplicate), `unsyncedForUser`/`unsyncedAll`/`unsyncedForSource`,
`markSynced(timestampEpochSec, userSlot)` — marks EVERY source's row with
that key, mirroring the Garmin journal's composite key — `latest`,
`allForUser`, `allForSource`, `allForSources` (the Measurements page's
unified query), `deleteAll`, `deleteForUser`. Pure interface — unit tests
fake it; Room annotations stay here and downstream code needs no Android.

**Tests.** `OmronBpRepositoryTest` (fake DAO); `BpSyncCoordinatorTest`.
