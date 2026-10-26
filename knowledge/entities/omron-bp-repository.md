---
type: entity
title: OmronBpRepository — never-throws DAO gateway
status: current
generated: 2026-10-26
tags: [room, repository]
---
**Responsibility.** Gateway over `BloodPressureDao`; every DAO failure maps
onto an empty/failed result, never an exception (ADR-09). Depends ONLY on the
DAO interface + an injected `onError` hook (no android.util.Log — plain
JUnit). File: `omron/data/OmronBpRepository.kt`.

**`StoreResult(insertedCount, duplicatesCount, success)`.** `success=false`
means a DAO failure — nothing was written and the counts carry no meaning
(xlg.15 added the flag because zero-inserted alone cannot tell "all
duplicates / empty batch — rows ARE durably in Room" from "DAO failed"). The
read orchestrator gates anchor advance, fallback clear AND deferred journal
commits on it (ADR-03).

**API.** `storeReadings(readings, sourceId)` insert-ignore (tags rows with
the source id); `unsynced(slot?)`, `latest`, `allForUser`, `allForSource`,
`unsyncedForSource`, `allForSources` (returns `StoredBpReading` — row +
sourceId + synced), `markSynced(ts, slot)`, `deleteAll`, `deleteForUser`.
`CancellationException` rethrown by the internal `failed()` helper.

**Tests.** `OmronBpRepositoryTest`.
