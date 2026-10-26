---
type: adr
title: ADR-03 Read-orchestration guarantees in SourceReadUseCase
status: accepted
generated: 2026-10-26
tags: [concurrency, data-loss, blood-pressure]
---
## Context
One physical monitor serves one read pass at a time; a pass can die mid-session
(cancellation, process death) after the device already consumed state. The
xlg.5/xlg.15/xlg.16 reviews pinned three data-loss windows and fixed them.

## Decision
`devices/SourceReadUseCase.kt` runs each pass under these rules:
- **Per-source mutex** (one `Mutex` per source id, different sources read in
  parallel); the pass runs on the **stored row reloaded under the lock**, not
  the caller's snapshot.
- **`fullReadFallbackPending` is persisted BEFORE a Current-plan read runs**
  (one row write under the read lock). The Omron device resets its unread
  counters MID-SESSION — a cancel/process-death after the reset would silently
  lose the window otherwise. The next pass then runs one full-ring History
  plan to recover; the configured sync mode is never rewritten.
- **`lastReadAnchor` advances only on Success** (to the newest ingested
  reading, never regressing). `StoreResult.success=false` (DAO failure) skips
  the anchor advance AND the journal commit — the flagged source re-reads.
- **`DeferredCommitSource`**: the 0x1810 fingerprint journal commits only
  AFTER the store confirms the insert (`commitDelivered()` runs only when
  `StoreResult.success`). Worst case one duplicate row, never loss.
- History-less sources (Health Connect shape) are excluded from the fallback:
  their own anchor advances only on success, so a retry re-reads exactly what
  the failed pass missed.

## Consequences
- Over-flagging costs one redundant full-ring History read after an early
  cancellation; under-flagging could cost readings — the trade is deliberate.
- Failed reads stay fully retriable; no failure sequence loses readings a
  History re-read could deliver. Eight interleaving tests pin this in
  `SourceReadUseCaseTest`.
