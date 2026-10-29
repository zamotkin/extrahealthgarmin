---
type: entity
title: GarminRepository — the UI-facing Garmin gateway
status: current
generated: 2026-10-26
tags: [garmin, data]
---
**Responsibility.** `data/GarminRepository.kt` implements
`GarminUploadOrchestrator`: dedups against journals, uploads as one batch,
reports `UploadOutcome`; never throws. Collaborators: `GarminTokenStore`,
`HttpGarminRemote` (the `GarminRemote` seam), `UploadJournal` (weigh-ins,
timestamp key) and `BpUploadJournal` (composite key).

**`uploadBloodPressure(records)`.** Single-flight `Mutex` serializes
filterNew → upload → markUploaded so concurrent callers cannot double-upload
(cross-instance concurrency is defused by the journal's merge-on-save).
Journals ONLY confirmed records (`Partial` → the provably imported subset;
everything else stays retriable). `GarminAuthError` clears tokens and answers
`LoginRequired`; `GarminTooManyRequestsError`/connection errors → `Retryable`.

**`signOut` gate semantics.** `signOut()` clears ONLY the tokens (journals and
sync state are not this repository's to touch) and drops any half-finished
MFA state. `persistRotatedTokens` is suppressed while signed out: a pass that
loaded its tokens before the sign-out must not write them back, or the
session resurrects itself (the xlg.7 review race).

**`uploadNewRecords`** is the weigh-in path (dedup + profile-BMI enrichment +
FIT weight_scale upload). **Tests.** `GarminRepositoryTest`,
`GarminRepositoryBpTest`, `GarminRepositoryRecordsTest`.
