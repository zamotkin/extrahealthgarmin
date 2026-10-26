---
type: entity
title: BpSyncCoordinator + BpUploadJournal — the BP upload pass
status: current
generated: 2026-10-26
tags: [garmin, sync]
---
**Responsibility.** One direct-upload pass scoped to ONE monitor user slot
(the M7's two slots are different people; the Garmin account is one person).
File: `omron/data/BpSyncCoordinator.kt`; journal: `garmin/BpUploadJournal.kt`.

**`syncOnce(userSlot): UploadOutcome`.** Takes the un-synced readings of the
slot from `OmronBpRepository`, hands the whole batch to the injected upload
(`GarminRepository.uploadBloodPressure`), and marks synced only records the
upload CONFIRMED. Self-healing: an `UploadOutcome.NotNeeded` over a non-empty
batch means the Garmin journal already covers it (e.g. markSynced was lost to
a DAO failure) — all pending records of the slot are then marked synced, so a
confirmed upload can never get stuck un-synced. Never throws; failures map
onto `Retryable`.

**`BpUploadJournal`** (key = `timestampEpochSec:userSlot`, file
`bp_upload_journal.json` over the `JournalPersistence` seam): `filterNew`
dedups against the journal AND within the batch; only `markUploaded` grows
it; every save MERGES (union with the persisted set) so a second instance can
never erase keys another persisted after its construction. `markSynced(ts,
slot)` consequently marks all sources' rows with that key.

**Tests.** `BpSyncCoordinatorTest`, `BpUploadJournalTest`,
`GarminRepositoryBpTest`.
